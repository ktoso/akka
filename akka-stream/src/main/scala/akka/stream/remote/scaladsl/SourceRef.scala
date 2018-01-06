/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.{ Done, NotUsed }
import akka.actor.ActorRef
import akka.actor.Status.Failure
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.remote.StreamRefs
import akka.stream.remote.StreamRefs.{ CumulativeDemand, SequencedOnNext }
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.scaladsl.{ FlowOps, Sink, Source }
import akka.stream.stage._
import akka.util.{ ByteString, OptionVal }

import scala.concurrent.{ Future, Promise }
import scala.util.Try

object SourceRef {

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Diagram: TODO a nice diagram
   */
  def sink[T](): Graph[SinkShape[T], Future[SourceRef[T]]] =
    Sink.fromGraph(new SourceRefOriginSink[T]())

  // TODO Implement using TCP
  // def bulkTransfer[T](): Graph[SinkShape[ByteString], SourceRef[ByteString]] = ???
}

final class SourceRefOriginSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], Future[SourceRef[T]]] {
  val in: Inlet[T] = Inlet[T](s"${Logging.simpleName(getClass)}.in")
  override def shape: SinkShape[T] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[SourceRef[T]]) = {
    val promise = Promise[SourceRef[T]]

    val logic = new TimerGraphStageLogic(shape) with InHandler with StageLogging {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] var remotePartner: OptionVal[ActorRef] = OptionVal.None

      override protected lazy val stageActorName = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] var self: GraphStageLogic.StageActor = _
      private[this] implicit def selfSender: ActorRef = self.ref

      // demand management ---
      private var remoteCumulativeDemandReceived: Long = 0L
      private var remoteCumulativeDemandConsumed: Long = 0L
      // end of demand management ---

      // early failure/completion management ---
      private var completedBeforeRemoteConnected: OptionVal[Try[Done]] = OptionVal.None
      // end of early failure/completion management ---

      override def preStart(): Unit = {
        self = getStageActor(initialReceive)
        log.warning("Allocated emitter: {}", self.ref)

        promise.success(new SourceRef(OptionVal(self.ref)))
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.CumulativeDemand(demand)) ⇒
          observeAndValidateSender(sender, "Illegal sender in CumulativeDemand")

          if (demand > remoteCumulativeDemandReceived) {
            remoteCumulativeDemandReceived = demand
            log.warning("Received cumulative demand [{}], consumable demand: [{}]", msg,
              remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
          }

          tryPull()
      }

      def tryPull(): Unit =
        if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in))
          pull(in)

      private def grabSequenced(in: Inlet[T]): SequencedOnNext[T] = {
        val onNext = SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
        remoteCumulativeDemandConsumed += 1
        onNext
      }

      override def onPush(): Unit = {
        val elem = grabSequenced(in)
        remotePartner.get ! elem // FIXME log error?
        log.warning("Sending sequenced: {} to {}", elem, remotePartner)
        tryPull()
      }

      @throws[StreamRefs.InvalidPartnerActorException]
      def observeAndValidateSender(sender: ActorRef, msg: String): Unit =
        if (remotePartner.isEmpty) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", sender)
          remotePartner = OptionVal(sender)

          if (completedBeforeRemoteConnected.isDefined) completedBeforeRemoteConnected.get match {
            case scala.util.Failure(ex) ⇒
              log.warning("Stream already terminated with exception before remote side materialized, failing now.")
              sender ! StreamRefs.RemoteStreamFailure(ex.getMessage)
              failStage(ex)

            case scala.util.Success(Done) ⇒
              log.warning("Stream already completed before remote side materialized, failing now.")
              sender ! StreamRefs.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
              completeStage()
          }
          else {
            self.watch(sender)
          }
        } else if (sender != remotePartner.get) {
          throw StreamRefs.InvalidPartnerActorException(sender, remotePartner.get, msg)
        }

      //      @throws[StreamRefs.InvalidSequenceNumberException]
      //      def observeAndValidateSequenceNr(seqNr: Long, msg: String): Unit =
      //        if (isInvalidSequenceNr(seqNr)) {
      //          throw StreamRefs.InvalidSequenceNumberException(expectingSeqNr, seqNr, msg)
      //        } else {
      //          expectingSeqNr += 1
      //        }
      //      def isInvalidSequenceNr(seqNr: Long): Boolean =
      //        seqNr != expectingSeqNr

      override def onUpstreamFailure(ex: Throwable): Unit = if (remotePartner.isDefined) {
        remotePartner.get ! StreamRefs.RemoteStreamFailure(ex.getMessage)
        self.unwatch(remotePartner.get)
        super.onUpstreamFailure(ex)
      } else {
        completedBeforeRemoteConnected = OptionVal(scala.util.Failure(ex))
        // not terminating on purpose, since other side may subscribe still and then we want to fail it
        setKeepGoing(true)
      }

      override def onUpstreamFinish(): Unit = if (remotePartner.isDefined) {
        remotePartner.get ! StreamRefs.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
        self.unwatch(remotePartner.get)
        super.onUpstreamFinish()
      } else {
        completedBeforeRemoteConnected = OptionVal(scala.util.Success(Done))
        // not terminating on purpose, since other side may subscribe still and then we want to complete it
        setKeepGoing(true)
      }

      setHandler(in, this)
    }

    (logic, promise.future)
  }

}

/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust
 */
final class SourceRef[T](private[akka] val originRef: OptionVal[ActorRef]) extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {
  val out: Outlet[T] = Outlet[T](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SinkRef[T]]()

    val logic = new TimerGraphStageLogic(shape) with StageLogging with OutHandler {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)
      private[this] lazy val settings = streamRefsMaster.settings

      override protected lazy val stageActorName = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] var self: GraphStageLogic.StageActor = _
      private[this] implicit def selfSender: ActorRef = self.ref

      // demand management ---
      private val highDemandWatermark = 16

      private var expectingSeqNr: Long = 0L
      private var localCumulativeDemand: Long = 0L // initialized in preStart with settings.initialDemand

      private val receiveBuffer = FixedSizeBuffer[T](highDemandWatermark)

      // TODO configurable?
      // Request strategies talk in terms of Request(n), which we need to translate to cumulative demand
      // TODO the MaxInFlightRequestStrategy is likely better for this use case, yet was a bit weird to use so this one for now
      private val requestStrategy: RequestStrategy = WatermarkRequestStrategy(highWatermark = highDemandWatermark)
      // end of demand management ---

      // initialized with the originRef if present, that means we're the "remote" for an already active Source on the other side (the "origin")
      // null otherwise, in which case we allocated first -- we are the "origin", and awaiting the other side to start when we'll receive this ref
      private var remotePartner: ActorRef = originRef.orNull

      override def preStart(): Unit = {
        localCumulativeDemand = settings.initialDemand.toLong

        self = getStageActor(initialReceive)
        log.warning("Allocated receiver: {}", self.ref)

        promise.success(new SinkRef(self.ref, settings.initialDemand))
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit =
        if (remotePartner ne null) {
          val remainingRequested = java.lang.Long.min(highDemandWatermark, localCumulativeDemand - expectingSeqNr).toInt
          val addDemand = requestStrategy.requestDemand(remainingRequested)

          // only if demand has increased we shoot it right away
          // otherwise it's the same demand level, so it'd be triggered via redelivery anyway
          if (addDemand > 0) {
            localCumulativeDemand += addDemand
            val demand = StreamRefs.CumulativeDemand(localCumulativeDemand)

            log.warning("[{}] Demanding until [{}] (+{})", stageActorName, localCumulativeDemand, addDemand)
            remotePartner ! demand
            scheduleDemandRedelivery()
          }
        }

      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"
      def scheduleDemandRedelivery() = scheduleOnce(DemandRedeliveryTimerKey, settings.demandRedeliveryInterval)
      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case DemandRedeliveryTimerKey ⇒
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", stageActorName, localCumulativeDemand)
          remotePartner ! StreamRefs.CumulativeDemand(localCumulativeDemand)
          scheduleDemandRedelivery()
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.SequencedOnNext(seqNr, payload)) ⇒
          observeAndValidateSender(sender, "Illegal sender in SequencedOnNext")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in SequencedOnNext")
          log.warning("Received seq {} from {}", msg, sender)

          triggerCumulativeDemand()
          tryPush(payload)

        case (sender, StreamRefs.RemoteStreamCompleted(seqNr)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkCompleted")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in RemoteSinkCompleted")
          log.debug("The remote stream has completed, completing as well...")

          self.unwatch(sender)
          completeStage()

        case (sender, StreamRefs.RemoteStreamFailure(reason)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkFailure")
          log.debug("The remote stream has failed, failing (reason: {})", reason)

          self.unwatch(sender)
          failStage(StreamRefs.RemoteStreamRefActorTerminatedException(s"Remote stream (${sender.path}) failed, reason: $reason"))
      }

      def tryPush(): Unit =
        if (isAvailable(out) && receiveBuffer.nonEmpty) {
          val elem = receiveBuffer.dequeue()
          log.debug(s"PUSHING SIGNALED ${elem} (capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})") // TODO cleanup
          push(out, elem)
        }
      def tryPush(payload: Any): Unit =
        if (isAvailable(out)) {
          if (receiveBuffer.nonEmpty) {
            val elem = receiveBuffer.dequeue()
            push(out, elem)
            receiveBuffer.enqueue(payload.asInstanceOf[T])
            log.debug(s"PUSHING SIGNALED ${elem} BUFFERING payload" + payload + s"(capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})") // TODO cleanup
          } else {
            push(out, payload.asInstanceOf[T])
            log.debug(s"PUSHING DIRECTLY ${payload}") // TODO cleanup
          }
        } else {
          receiveBuffer.enqueue(payload.asInstanceOf[T])
          log.debug("PUSHING BUFFERING payload" + payload + s"(capacity: ${receiveBuffer.used}/${receiveBuffer.capacity})") // TODO cleanup
        }

      @throws[StreamRefs.InvalidPartnerActorException]
      def observeAndValidateSender(sender: ActorRef, msg: String): Unit =
        if (remotePartner == null) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", sender)
          remotePartner = sender
          self.watch(sender)
        } else if (sender != remotePartner) {
          throw StreamRefs.InvalidPartnerActorException(sender, remotePartner, msg)
        }

      @throws[StreamRefs.InvalidSequenceNumberException]
      def observeAndValidateSequenceNr(seqNr: Long, msg: String): Unit =
        if (isInvalidSequenceNr(seqNr)) {
          throw StreamRefs.InvalidSequenceNumberException(expectingSeqNr, seqNr, msg)
        } else {
          expectingSeqNr += 1
        }
      def isInvalidSequenceNr(seqNr: Long): Boolean =
        seqNr != expectingSeqNr

      setHandler(out, this)
    }
    (logic, promise.future) // FIXME we'd want to expose just the ref!
  }

  override def toString: String =
    s"${Logging.simpleName(getClass)}($originRef)}"
}

