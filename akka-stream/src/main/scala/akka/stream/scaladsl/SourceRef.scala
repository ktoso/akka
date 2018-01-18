/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.language.implicitConversions
import java.util.concurrent.CompletionStage

import akka.NotUsed
import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.actor.{ RequestStrategy, WatermarkRequestStrategy }
import akka.stream.impl.FixedSizeBuffer
import akka.stream.StreamRefs
import akka.stream.impl.StreamRefsMaster
import akka.stream.stage._
import akka.util.{ OptionVal, PrettyDuration }

import scala.concurrent.{ Future, Promise }
import scala.language.implicitConversions

private[stream] final case class SourceRefImpl[T](initialPartnerRef: OptionVal[ActorRef]) extends SourceRef[T] {
  def source: Source[T, NotUsed] =
    Source.fromGraph(new SourceRefStage(initialPartnerRef)).mapMaterializedValue(_ ⇒ NotUsed)

  def getSource: javadsl.Source[T, NotUsed] = source.asJava
}

// FIXME: move to impl package
private[stream] final class SourceRefStage[T](
  val initialPartnerRef: OptionVal[ActorRef]
) extends GraphStageWithMaterializedValue[SourceShape[T], Future[SinkRef[T]]] {

  val out: Outlet[T] = Outlet[T](s"${Logging.simpleName(getClass)}.out")
  override def shape = SourceShape.of(out)

  private def initialRefName =
    if (initialPartnerRef.isDefined) initialPartnerRef.get
    else "<no-initial-ref>"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[SinkRef[T]]) = {
    val promise = Promise[SinkRef[T]]()

    val logic = new TimerGraphStageLogic(shape) with StageLogging with OutHandler {
      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)

      // settings ---
      import StreamRefAttributes._
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] lazy val subscriptionTimeout = inheritedAttributes
        .get[StreamRefAttributes.SubscriptionTimeout](SubscriptionTimeout(settings.subscriptionTimeout))
      // end of settings ---

      override protected lazy val stageActorName: String = streamRefsMaster.nextSinkRefTargetSourceName()
      private[this] var self: GraphStageLogic.StageActor = _
      private[this] implicit def selfSender: ActorRef = self.ref

      val SubscriptionTimeoutTimerKey = "SubscriptionTimeoutKey"
      val DemandRedeliveryTimerKey = "DemandRedeliveryTimerKey"

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
      private var partnerRef: OptionVal[ActorRef] = OptionVal.None
      private def getPartnerRef = partnerRef.get

      override def preStart(): Unit = {
        receiveBuffer = FixedSizeBuffer[T](settings.bufferCapacity)
        requestStrategy = WatermarkRequestStrategy(highWatermark = receiveBuffer.capacity)

        self = getStageActor(initialReceive)
        log.debug("[{}] Allocated receiver: {}", stageActorName, self.ref)
        if (initialPartnerRef.isDefined) // this will set the partnerRef
          observeAndValidateSender(initialPartnerRef.get, "<no error case here, definitely valid>")

        promise.success(new SinkRefImpl(OptionVal(self.ref)))

        scheduleOnce(SubscriptionTimeoutTimerKey, subscriptionTimeout.timeout)
      }

      override def onPull(): Unit = {
        tryPush()
        triggerCumulativeDemand()
      }

      def triggerCumulativeDemand(): Unit = {
        val i = receiveBuffer.remainingCapacity - localRemainingRequested
        if (partnerRef.isDefined && i > 0) {
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

      def scheduleDemandRedelivery() = scheduleOnce(DemandRedeliveryTimerKey, settings.demandRedeliveryInterval)

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case SubscriptionTimeoutTimerKey ⇒
          val ex = StreamRefs.StreamRefSubscriptionTimeoutException(
            // we know the future has been competed by now, since it is in preStart
            s"[$stageActorName] Remote side did not subscribe (materialize) handed out Sink reference [${promise.future.value}]," +
              s"within subscription timeout: ${PrettyDuration.format(subscriptionTimeout.timeout)}!")

          throw ex // this will also log the exception, unlike failStage; this should fail rarely, but would be good to have it "loud"

        case DemandRedeliveryTimerKey ⇒
          log.debug("[{}] Scheduled re-delivery of demand until [{}]", stageActorName, localCumulativeDemand)
          getPartnerRef ! StreamRefs.CumulativeDemand(localCumulativeDemand)
          scheduleDemandRedelivery()
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (sender, msg @ StreamRefs.OnSubscribeHandshake(remoteRef)) ⇒
          cancelTimer("SubscriptionTimeoutTimerKey")
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
      def observeAndValidateSender(partner: ActorRef, msg: String): Unit =
        if (partnerRef.isEmpty) {
          log.debug("Received first message from {}, assuming it to be the remote partner for this stage", partner)
          partnerRef = OptionVal(partner)
          self.watch(partner)
        } else if (partnerRef.isDefined && partner != getPartnerRef) {
          val ex = StreamRefs.InvalidPartnerActorException(partner, getPartnerRef, msg)
          partner ! StreamRefs.RemoteStreamFailure(ex.getMessage)
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
    s"${Logging.simpleName(getClass)}($initialRefName)}"
}

