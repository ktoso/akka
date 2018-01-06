/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.remote.StreamRefs
import akka.stream.remote.impl.StreamRefsMaster
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage._
import akka.util.OptionVal

import scala.concurrent.{ Future, Promise }

object SourceRef {

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Diagram: TODO a nice diagram
   */
  def sink[T](): Graph[SinkShape[T], Future[SourceRef[T]]] =
    Sink.fromGraph(new SinkRef[T](OptionVal.None, materializeSourceRef = true))

  // TODO Implement using TCP
  // def bulkTransfer[T](): Graph[SinkShape[ByteString], SourceRef[ByteString]] = ???

  implicit def convertRefToSource[T](ref: SourceRef[T]): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(ref)
}

/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust
 */
final class SourceRef[T](private[akka] val initialOriginRef: OptionVal[ActorRef]) extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {

  val out: Outlet[T] = Outlet[T](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  /**
   * Convenience method for obtaining a [[Source]] from this [[SourceRef]] which is a [[Graph]].
   *
   * Please note that an implicit conversion is also provided in [[SourceRef]].
   */
  def source = Source.fromGraph(this)
  /**
   * Method used for obtaining a [[akka.stream.javadsl.Source]] from this [[SourceRef]] which is a [[Graph]].
   */
  def getSource = akka.stream.javadsl.Source.fromGraph(this)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[SinkRef[T]]) = {
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
      private var getPartnerRef: ActorRef = initialOriginRef.orNull

      override def preStart(): Unit = {
        localCumulativeDemand = settings.initialDemand.toLong

        self = getStageActor(initialReceive)
        log.warning("Allocated receiver: {}", self.ref)

        promise.success(new SinkRef(OptionVal(self.ref), materializeSourceRef = true))
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit =
        if (getPartnerRef ne null) {
          val remainingRequested = java.lang.Long.min(highDemandWatermark, localCumulativeDemand - expectingSeqNr).toInt
          val addDemand = requestStrategy.requestDemand(remainingRequested)

          // only if demand has increased we shoot it right away
          // otherwise it's the same demand level, so it'd be triggered via redelivery anyway
          if (addDemand > 0) {
            localCumulativeDemand += addDemand
            val demand = StreamRefs.CumulativeDemand(localCumulativeDemand)

            log.warning("[{}] Demanding until [{}] (+{})", stageActorName, localCumulativeDemand, addDemand)
            getPartnerRef ! demand
            scheduleDemandRedelivery()
          }
        }

      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"
      def scheduleDemandRedelivery() = scheduleOnce(DemandRedeliveryTimerKey, settings.demandRedeliveryInterval)
      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case DemandRedeliveryTimerKey ⇒
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", stageActorName, localCumulativeDemand)
          getPartnerRef ! StreamRefs.CumulativeDemand(localCumulativeDemand)
          scheduleDemandRedelivery()
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.OnSubscribeHandshake(remoteRef)) ⇒
          observeAndValidateSender(remoteRef, "Illegal sender in SequencedOnNext")
          log.debug("Received handshake {} from {}", msg, sender)

          triggerCumulativeDemand()

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

        case (_, Terminated(ref)) ⇒
          if (getPartnerRef == ref)
            failStage(StreamRefs.RemoteStreamRefActorTerminatedException(s"The remote partner ${getPartnerRef} has terminated! " +
              s"Tearing down this side of the stream as well."))
        // else this should not have happened, and we ignore this -- someone may have been doing weird things!
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
        if (getPartnerRef == null) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", sender)
          getPartnerRef = sender
          self.watch(sender)
        } else if (sender != getPartnerRef) {
          val ex = StreamRefs.InvalidPartnerActorException(sender, getPartnerRef, msg)
          sender ! StreamRefs.RemoteStreamFailure(ex.getMessage)
          throw ex
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
    s"${Logging.simpleName(getClass)}($initialOriginRef)}"
}

