/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import java.util

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.annotation.InternalApi
import akka.pattern.FutureRef
import akka.stream._
import akka.stream.impl.SinkModule
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.remote.{ RemoteStreamRefActorTerminatedException, StreamRefs }
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.{ ByteString, OptionVal }
import org.reactivestreams.Subscriber

import scala.concurrent.{ Future, Promise }

object SinkRef {
  def source[T](): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(new SinkRefTargetSource[T]()) // TODO settings?

  def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = {
    ???
  }
}

///**
// * INTERNAL API
// */
//@InternalApi private[akka] final class SinkRefModule[In](val attributes: Attributes, shape: SinkShape[In]) extends SinkModule[In, ActorRef](shape) {
//
//  override def create(context: MaterializationContext): (Subscriber[In], ActorRef) = {
//    val system = ActorMaterializerHelper.downcast(context.materializer)
//    val subscriberRef = system.actorOf(context.copy(islandName = ""), SinkRefActor.props)
//    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
//  }
//
//  override protected def newInstance(shape: SinkShape[In]): SinkModule[In, ActorRef] = new SinkRefModule[In](attributes, shape)
//  override def withAttributes(attr: Attributes): SinkModule[In, ActorRef] = new SinkRefModule[In](attr, amendShape(attr))
//}

/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust
 */
final class SinkRefTargetSource[T] extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {
  val out: Outlet[T] = Outlet[T]("SinkRef.source.out")
  override def shape = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SinkRef[T]]()

    val logic = new GraphStageLogic(shape) with StageLogging with OutHandler {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)

      private[this] var self: GraphStageLogic.StageActor = _
      private[this] lazy val selfActorName = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] implicit def selfSender: ActorRef = self.ref

      val initialDemand = 4L // TODO get from config as well as attributes

      var localCumulativeDemand = 0L
      var remotePartner: ActorRef = _

      override def preStart(): Unit = {
        self = getStageActor(initialReceive, name = selfActorName)

        //        receiverRef = master.allocateReceiver(self.ref)
        log.warning("Allocated receiver: {}", self.ref)

        promise.success(new SinkRef(self.ref, initialDemand))
      }

      override def onPull(): Unit = {
        localCumulativeDemand += 1
        triggerDemand()
      }

      // FIXME this should have some smarter strategy than pulling one by one
      def triggerDemand(): Unit = if (remotePartner ne null) {
        localCumulativeDemand += 1
        remotePartner ! StreamRefs.CumulativeDemand(localCumulativeDemand)
        log.debug("[{}] Demanding until {}", selfActorName, StreamRefs.CumulativeDemand(localCumulativeDemand))
      }

      def runningReceive(activeSender: ActorRef): ((ActorRef, Any)) ⇒ Unit = {
        case (`activeSender`, StreamRefs.SequencedOnNext(seqNr, payload: T)) ⇒
          // FIXME fail or wait if the sequence number is not strictly in order with the expected one
          log.warning("Received seq {} from {}", StreamRefs.SequencedOnNext(seqNr, payload: T), activeSender)
          remotePartner = activeSender

          triggerDemand()
          push(out, payload) // TODO only if allowed to push

        case (`activeSender`, StreamRefs.RemoteSinkCompleted(seqNr)) ⇒
          log.info("The remote Sink has completed at {}, completing this source as well...", seqNr)
          // FIXME fail or wait if the sequence number is not strictly in order with the expected one
          completeStage()

        case (sender, StreamRefs.RemoteSinkFailure(reason)) ⇒
          log.info("The remote Sink has failed, failing (reason: {})", reason)
          failStage(new RuntimeException(s"Remote Sink failed, reason: $reason"))
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, StreamRefs.SequencedOnNext(seqNr, payload: T)) ⇒
          log.warning("Received seq {} from {}", StreamRefs.SequencedOnNext(seqNr, payload: T), sender)
          remotePartner = sender
          self.watch(sender) // FIXME?

          triggerDemand()
          push(out, payload) // TODO only if allowed to push
          getStageActor(runningReceive(sender)) // become running

        case (sender, StreamRefs.RemoteSinkCompleted(seqNr)) ⇒
          // FIXME fail or wait if the sequence number is not strictly in order with the expected one
          log.info("The remote Sink has completed, completing this source as well...")
          self.unwatch(sender)
          completeStage()

        case (sender, StreamRefs.RemoteSinkFailure(reason)) ⇒
          log.info("The remote Sink has failed, failing (reason: {})", reason)
          self.unwatch(sender)
          failStage(new RuntimeException(s"Remote Sink failed, reason: $reason"))
      }

      setHandler(out, this)
    }
    (logic, promise.future) // FIXME we'd want to expose just the ref!
  }
}

/**
 * The "handed out" side of a SinkRef. It powers a Source on the other side.
 * TODO naming!??!?!!?!?!?!
 *
 * Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef
 */
final class SinkRef[In] private[akka] (
  private[akka] val targetRef:     ActorRef,
  private[akka] val initialDemand: Long
) extends GraphStage[SinkShape[In]] with Serializable { stage ⇒
  import akka.stream.remote.StreamRefs._

  val in = Inlet[In](s"SinkRef($targetRef).in")
  override def shape: SinkShape[In] = SinkShape.of(in)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging with InHandler {

    private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
    private[this] lazy val selfActorName = streamRefsMaster.nextSinkRefName()

    // we assume that there is at least SOME buffer space
    private[this] var cumulativeDemand = initialDemand // FIXME figure it out, OR we need a handshake

    // FIXME this one will be sent over remoting so we have to be able to make that work
    private[this] var grabbedSequenceNr = 0L
    private[this] var self: GraphStageLogic.StageActor = _
    implicit def selfSender: ActorRef = self.ref

    override def preStart(): Unit = {

      self = getStageActor({
        case (_, Terminated(`targetRef`)) ⇒
          failStage(failRemoteTerminated())

        case (sender, CumulativeDemand(d)) ⇒
          validatePartnerRef(sender)

          log.warning("Received cumulative demand {} from {}", CumulativeDemand(d), targetRef)
          if (cumulativeDemand < d) cumulativeDemand = d
          tryPull()
      }, selfActorName)

      self.watch(targetRef)

      log.warning("Created SinkRef, pointing to remote Sink receiver: {}, local worker: {}", targetRef, self)

      pull(in)
    }

    override def onPush(): Unit = {
      val elem = grabSequenced(in)
      targetRef ! elem
      log.warning("Sending sequenced: {} to {}", elem, targetRef)
      tryPull()
    }

    private def tryPull() = {
      if (grabbedSequenceNr < cumulativeDemand && !hasBeenPulled(in))
        pull(in)
    }

    private def grabSequenced[T](in: Inlet[T]): SequencedOnNext[T] = {
      grabbedSequenceNr += 1
      SequencedOnNext(grabbedSequenceNr, grab(in))
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      targetRef ! StreamRefs.RemoteSinkFailure(ex.getMessage) // TODO yes / no? At least the message I guess
      self.unwatch(targetRef)
      super.onUpstreamFailure(ex)
    }

    override def onUpstreamFinish(): Unit = {
      targetRef ! StreamRefs.RemoteSinkCompleted(grabbedSequenceNr + 1)
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

  override def toString = s"SinkRef($targetRef)"

}
