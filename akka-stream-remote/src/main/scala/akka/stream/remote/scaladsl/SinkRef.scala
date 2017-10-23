/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.NotUsed
import akka.actor.{ ActorRef, Terminated }
import akka.stream._
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, GraphStageWithMaterializedValue, InHandler }
import akka.util.ByteString

/** Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef */
final class SinkRef[In] private[akka] (private[akka] val targetRef: ActorRef) extends GraphStage[SinkShape[In]] {
  import StreamRefs._

  val in = Inlet[In](s"SinkRef($targetRef).in")
  override def shape: SinkShape[In] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler {

    // we assume that there is at least SOME buffer space
    private var knownRemoteDemand = 4L // FIXME figure it out, OR we need a handshake

    // FIXME this one will be sent over remoting so we have to be able to make that work
    val selfRef = getStageActor {
      case (_, Terminated(`targetRef`)) ⇒
        failStage(failException())
      case (`targetRef`, Demand(d)) ⇒
        // TODO do we need Sequenced(Demand(?

        if (knownRemoteDemand == 0) {
          knownRemoteDemand += d
          tryPull()
        } else tryPull()
    }
    selfRef.watch(targetRef)

    override def onPush(): Unit = {
      if (knownRemoteDemand > 0) {
        targetRef ! grabSequenced(in)
        tryPull()
      }
    }

    private def tryPull() = {
      if (knownRemoteDemand > 0) {
        knownRemoteDemand -= 1
        pull(in)
      }
    }

    private var sequenceNr = 0
    private def grabSequenced[T](in: Inlet[T]): Sequenced[T] = {
      val s = Sequenced(sequenceNr, grab(in))
      sequenceNr += 1
      s
    }

  }

  private def failException() = {
    RemoteStreamRefActorTerminatedException(s"Remote target receiver of data ${targetRef} terminated. Local stream terminating, message loss (on remote side) may have happened.")
  }
}

class SinkRefSource[T] extends GraphStageWithMaterializedValue[SourceShape[T], SinkRef[T]] {
  val out = Outlet[T]("SinkRefSource.out")
  override def shape = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    var mat: Option[SinkRef[T]] = None

    /*
     * This stage can only handle a single "sender" (it does not merge values);
     * The first that pushes is assumed the one we are to trust
     */
    val logic = new GraphStageLogic(shape) {
      def runningReceive(activeSender: ActorRef): ((ActorRef, Any)) ⇒ Unit = {
        case (`activeSender`, msg) ⇒

      }
      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, StreamRefs.Sequenced(seqNr, payload: T)) ⇒
          self.watch(sender) // FIXME?
          sender ! StreamRefs.Demand(16) // TODO configure demand management
          push(out, payload) // TODO only if allowed to push
          getStageActor(runningReceive(sender)) // become running
      }
      lazy val self = getStageActor(initialReceive)

      mat = Some(new SinkRef(self.ref))

    }
    (logic, mat.get)
  }
}

object SinkRef {
  def source[T](): Source[T, SinkRef[T]] = Source.fromGraph(new SinkRefSource[T]()) // TODO settings?
  def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = {
    ???
  }
}
