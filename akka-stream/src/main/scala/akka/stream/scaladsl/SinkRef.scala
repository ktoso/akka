/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.Done
import akka.actor.{ ActorRef, Terminated }
import akka.event.Logging
import akka.stream._
import akka.stream.StreamRefs
import akka.stream.impl.StreamRefsMaster
import akka.stream.stage._
import akka.util.{ OptionVal, PrettyDuration }

import scala.concurrent.{ Future, Promise }
import scala.util.Try

object SinkRef {
  def source[T](): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(new SourceRef[T](OptionVal.None, canMaterializeSinkRef = true))

  // TODO Implement using TCP
  // steps:
  // - lazily, but once bind a port
  // - advertise to other side that they may send data into this port
  // -- "passive mode" ftp ;-)
  // def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = ???
}

/**
 * The dual of SourceRef.
 *
 * This is the "handed out" side of a SinkRef. It powers a Source on the other side.
 *
 * Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef.
 *
 * We do not materialize the refs back and forth, which is why the 2nd param.
 */
final class SinkRef[In] private[akka] (
  private[akka] val initialPartnerRef:       OptionVal[ActorRef],
  private[akka] val canMaterializeSourceRef: Boolean
) extends akka.stream.javadsl.SinkRef[In] {
  import akka.stream.StreamRefs._

  val in = {
    val inletName = s"${Logging.simpleName(getClass)}($initialRefName).in"
    Inlet[In](inletName)
  }
  override def shape: SinkShape[In] = SinkShape.of(in)

  private def initialRefName =
    if (initialPartnerRef.isDefined) initialPartnerRef.get
    else "<no-initial-ref>"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[SourceRef[In]]
    if (!canMaterializeSourceRef) promise.failure(StreamRefs.CyclicMaterializationAttemptException(
      "SinkRef", otherStage = "SourceRef"))

    val logic = new TimerGraphStageLogic(shape) with StageLogging with InHandler {

      private[this] lazy val streamRefsMaster = StreamRefsMaster(ActorMaterializerHelper.downcast(materializer).system)

      // settings ---
      import StreamRefAttributes._
      private[this] lazy val settings = streamRefsMaster.settings

      private[this] lazy val subscriptionTimeout = inheritedAttributes
        .get[StreamRefAttributes.SubscriptionTimeout](SubscriptionTimeout(settings.subscriptionTimeout))
      // end of settings ---

      override protected lazy val stageActorName: String = streamRefsMaster.nextSinkRefName()
      private[this] var self: GraphStageLogic.StageActor = _
      implicit def selfSender: ActorRef = self.ref

      private var partnerRef: OptionVal[ActorRef] = OptionVal.None
      private def getPartnerRef: ActorRef =
        if (partnerRef.isDefined) partnerRef.get
        else throw StreamRefs.TargetRefNotInitializedYetException()

      val SubscriptionTimeoutTimerKey = "SubscriptionTimeoutKey"

      // demand management ---
      private var remoteCumulativeDemandReceived: Long = 0L
      private var remoteCumulativeDemandConsumed: Long = 0L
      // end of demand management ---

      private var completedBeforeRemoteConnected: OptionVal[Try[Done]] = OptionVal.None

      override def preStart(): Unit = {
        self = getStageActor(initialReceive)
        if (initialPartnerRef.isDefined) // this will set the `partnerRef`
          observeAndValidateSender(initialPartnerRef.get, "Illegal initialPartnerRef! This would be a bug in the SinkRef usage or impl.")

        log.debug("Created SinkRef, pointing to remote Sink receiver: {}, local worker: {}", initialPartnerRef, self.ref)

        if (canMaterializeSourceRef)
          promise.success(new SourceRef(OptionVal(self.ref), canMaterializeSinkRef = false))

        if (partnerRef.isDefined) {
          getPartnerRef ! StreamRefs.OnSubscribeHandshake(self.ref)
          tryPull()
        }

        scheduleOnce(SubscriptionTimeoutTimerKey, subscriptionTimeout.timeout)
      }

      lazy val initialReceive: ((ActorRef, Any)) ⇒ Unit = {
        case (_, Terminated(ref)) ⇒
          if (ref == getPartnerRef)
            failStage(RemoteStreamRefActorTerminatedException(s"Remote target receiver of data $partnerRef terminated. " +
              s"Local stream terminating, message loss (on remote side) may have happened."))

        case (sender, CumulativeDemand(d)) ⇒
          observeAndValidateSender(sender, "Illegal sender for CumulativeDemand")

          if (remoteCumulativeDemandReceived < d) {
            remoteCumulativeDemandReceived = d
            log.debug("Received cumulative demand [{}], consumable demand: [{}]", CumulativeDemand(d), remoteCumulativeDemandReceived - remoteCumulativeDemandConsumed)
          }

          tryPull()
      }

      override def onPush(): Unit = {
        val elem = grabSequenced(in)
        getPartnerRef ! elem
        log.debug("Sending sequenced: {} to {}", elem, getPartnerRef)
        tryPull()
      }

      private def tryPull() =
        if (remoteCumulativeDemandConsumed < remoteCumulativeDemandReceived && !hasBeenPulled(in)) {
          pull(in)
        }

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case SubscriptionTimeoutTimerKey ⇒
          val ex = StreamRefs.StreamRefSubscriptionTimeoutException(
            // we know the future has been competed by now, since it is in preStart
            s"[$stageActorName] Remote side did not subscribe (materialize) handed out Sink reference [${promise.future.value}]," +
              s"within subscription timeout: ${PrettyDuration.format(subscriptionTimeout.timeout)}!")

          throw ex // this will also log the exception, unlike failStage; this should fail rarely, but would be good to have it "loud"
      }

      private def grabSequenced[T](in: Inlet[T]): SequencedOnNext[T] = {
        val onNext = SequencedOnNext(remoteCumulativeDemandConsumed, grab(in))
        remoteCumulativeDemandConsumed += 1
        onNext
      }

      override def onUpstreamFailure(ex: Throwable): Unit =
        if (partnerRef.isDefined) {
          getPartnerRef ! StreamRefs.RemoteStreamFailure(ex.getMessage)
          self.unwatch(getPartnerRef)
          super.onUpstreamFailure(ex)
        } else {
          completedBeforeRemoteConnected = OptionVal(scala.util.Failure(ex))
          // not terminating on purpose, since other side may subscribe still and then we want to fail it
          setKeepGoing(true)
        }

      override def onUpstreamFinish(): Unit =
        if (partnerRef.isDefined) {
          getPartnerRef ! StreamRefs.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
          self.unwatch(getPartnerRef)
          super.onUpstreamFinish()
        } else {
          completedBeforeRemoteConnected = OptionVal(scala.util.Success(Done))
          // not terminating on purpose, since other side may subscribe still and then we want to complete it
          setKeepGoing(true)
        }

      @throws[StreamRefs.InvalidPartnerActorException]
      def observeAndValidateSender(partner: ActorRef, failureMsg: String): Unit = {
        if (partnerRef.isEmpty) {
          partnerRef = OptionVal(partner)
          self.watch(partner)

          if (completedBeforeRemoteConnected.isDefined)
            completedBeforeRemoteConnected.get match {
              case scala.util.Failure(ex) ⇒
                log.warning("Stream already terminated with exception before remote side materialized, failing now.")
                partner ! StreamRefs.RemoteStreamFailure(ex.getMessage)
                failStage(ex)

              case scala.util.Success(Done) ⇒
                log.warning("Stream already completed before remote side materialized, failing now.")
                partner ! StreamRefs.RemoteStreamCompleted(remoteCumulativeDemandConsumed)
                completeStage()
            }
        } else if (partner != getPartnerRef) {
          val ex = StreamRefs.InvalidPartnerActorException(partner, getPartnerRef, failureMsg)
          partner ! StreamRefs.RemoteStreamFailure(ex.getMessage)
          throw ex
        } // else: the ref is valid
      }

      private def failRemoteTerminated() =
        RemoteStreamRefActorTerminatedException(s"Remote target receiver of data $partnerRef terminated. " +
          s"Local stream terminating, message loss (on remote side) may have happened.")

      setHandler(in, this)

    }

    (logic, promise.future)
  }

  override def toString = s"${Logging.simpleName(getClass)}($initialRefName)"
}
