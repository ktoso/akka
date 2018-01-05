/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.remote.StreamRefs
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.OptionVal

import scala.concurrent.Future

object SinkRef {
  def source[T](): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(new SourceRef[T](OptionVal.None))

  // TODO Implement using TCP
  // def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = ???
}

/**
 * The "handed out" side of a SinkRef. It powers a Source on the other side.
 * TODO naming!??!?!!?!?!?!
 *
 * Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef
 */
final class SinkRef[In] private[akka] ( // TODO is it more of a SourceRefSink?
  private[akka] val targetRef:     ActorRef,
  private[akka] val initialDemand: Long
) extends GraphStage[SinkShape[In]] with Serializable { stage ⇒
  import akka.stream.remote.StreamRefs._

  val in = Inlet[In](s"${Logging.simpleName(getClass)}($targetRef).in")
  override def shape: SinkShape[In] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging with InHandler {

    private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
    private[this] override lazy val stageActorName = streamRefsMaster.nextSinkRefName()

    // we assume that there is at least SOME buffer space
    private[this] var remoteCumulativeDemandReceived = initialDemand

    // FIXME this one will be sent over remoting so we have to be able to make that work
    private[this] var remoteCumulativeDemandConsumed = 0L
    private[this] var self: GraphStageLogic.StageActor = _
    implicit def selfSender: ActorRef = self.ref

    override def preStart(): Unit = {
      self = getStageActor(initialReceive)
      self.watch(targetRef)

      log.warning("Created SinkRef, pointing to remote Sink receiver: {}, local worker: {}", targetRef, self)

      pull(in)
    }

    lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
      case (_, Terminated(`targetRef`)) ⇒
        failStage(failRemoteTerminated())

      case (sender, CumulativeDemand(d)) ⇒
        validatePartnerRef(sender)

        if (remoteCumulativeDemandReceived < d) {
          remoteCumulativeDemandReceived = d
          log.warning("Received cumulative demand [{}], consumable demand: [{}]", CumulativeDemand(d), remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
        }
        tryPull()
    }

    override def onPush(): Unit = {
      val elem = grabSequenced(in)
      targetRef ! elem
      log.warning("Sending sequenced: {} to {}", elem, targetRef)
      tryPull()
    }

    private def tryPull() =
      if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in))
        pull(in)

    private def grabSequenced[T](in: Inlet[T]): SequencedOnNext[T] = {
      val onNext = SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
      remoteCumulativeDemandConsumed += 1
      onNext
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      targetRef ! StreamRefs.RemoteStreamFailure(ex.getMessage) // TODO yes / no? At least the message I guess
      self.unwatch(targetRef)
      super.onUpstreamFailure(ex)
    }

    override def onUpstreamFinish(): Unit = {
      targetRef ! StreamRefs.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
      self.unwatch(targetRef)
      super.onUpstreamFinish()
    }

    setHandler(in, this)
  }

  private def validatePartnerRef(ref: ActorRef) = {
    if (ref != targetRef) throw new RuntimeException("Got demand from weird actor! Not the one I trust hmmmm!!!")
  }

  private def failRemoteTerminated() = {
    RemoteStreamRefActorTerminatedException(s"Remote target receiver of data ${targetRef} terminated. Local stream terminating, message loss (on remote side) may have happened.")
  }

  override def toString = s"${Logging.simpleName(getClass)}($targetRef)"

}
