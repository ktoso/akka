/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.StreamRefs
import akka.stream.impl.StreamRefsMaster
import akka.stream.stage._
import akka.util.OptionVal

import scala.concurrent.{ Future, Promise }

import scala.language.implicitConversions

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

      // we set the canPush flag when we were pulled, but buffer did not have any elements
      private[this] var canPush = false

      private var expectingSeqNr: Long = 0L
      private var localCumulativeDemand: Long = 0L
      private var localRemainingRequested: Int = 0

      private var receiveBuffer: FixedSizeBuffer.FixedSizeBuffer[T] = _ // initialized in preStart since depends on settings

      private var requestStrategy: RequestStrategy = _ // initialized in preStart since depends on receiveBuffer's size
      // end of demand management ---

      // initialized with the originRef if present, that means we're the "remote" for an already active Source on the other side (the "origin")
      // null otherwise, in which case we allocated first -- we are the "origin", and awaiting the other side to start when we'll receive this ref
      private var getPartnerRef: ActorRef = initialOriginRef.orNull

      override def preStart(): Unit = {
        receiveBuffer = FixedSizeBuffer[T](settings.bufferCapacity)
        requestStrategy = WatermarkRequestStrategy(highWatermark = receiveBuffer.capacity)

        self = getStageActor(initialReceive)
        log.debug("[{}] Allocated receiver: {}", stageActorName, self.ref)

        promise.success(new SinkRef(OptionVal(self.ref), materializeSourceRef = true))
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit = {
        val i = receiveBuffer.remainingCapacity - localRemainingRequested
        if (getPartnerRef != null && i > 0) {
          val addDemand = requestStrategy.requestDemand(receiveBuffer.used + localRemainingRequested)
          // only if demand has increased we shoot it right away
          // otherwise it's the same demand level, so it'd be triggered via redelivery anyway
          if (addDemand > 0) {
            localCumulativeDemand += addDemand
            localRemainingRequested += addDemand
            val demand = StreamRefs.CumulativeDemand(localCumulativeDemand)

            log.debug("[{}] Demanding until [{}] (+{})", stageActorName, localCumulativeDemand, addDemand)
            getPartnerRef ! demand
            scheduleDemandRedelivery()
          }
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
          log.debug("[{}] Received handshake {} from {}", stageActorName, msg, sender)

          triggerCumulativeDemand()

        case (sender, msg @ StreamRefs.SequencedOnNext(seqNr, payload)) ⇒
          observeAndValidateSender(sender, "Illegal sender in SequencedOnNext")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in SequencedOnNext")
          log.debug("[{}] Received seq {} from {}", stageActorName, msg, sender)

          tryPush(payload)
          triggerCumulativeDemand()

        case (sender, StreamRefs.RemoteStreamCompleted(seqNr)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkCompleted")
          observeAndValidateSequenceNr(seqNr, "Illegal sequence nr in RemoteSinkCompleted")
          log.debug("[{}] The remote stream has completed, completing as well...", stageActorName)

          self.unwatch(sender)
          completeStage()

        case (sender, StreamRefs.RemoteStreamFailure(reason)) ⇒
          observeAndValidateSender(sender, "Illegal sender in RemoteSinkFailure")
          log.warning("[{}] The remote stream has failed, failing (reason: {})", stageActorName, reason)

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
          push(out, elem)
        } else canPush = true

      def tryPush(payload: Any): Unit = {
        localRemainingRequested -= 1
        if (canPush) {
          canPush = false

          if (receiveBuffer.nonEmpty) {
            val elem = receiveBuffer.dequeue()
            push(out, elem)
            receiveBuffer.enqueue(payload.asInstanceOf[T])
          } else {
            push(out, payload.asInstanceOf[T])
          }
        } else {
          if (receiveBuffer.isFull) throw new IllegalStateException(s"Attempted to overflow buffer! Capacity: ${receiveBuffer.capacity}, incoming element: $payload, localRemainingRequested: ${localRemainingRequested}, localCumulativeDemand: ${localCumulativeDemand}")
          receiveBuffer.enqueue(payload.asInstanceOf[T])
        }
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

