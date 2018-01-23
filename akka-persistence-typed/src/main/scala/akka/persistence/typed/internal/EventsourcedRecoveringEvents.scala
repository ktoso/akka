/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.event.Logging
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity
import akka.persistence.typed.scaladsl.PersistentBehaviors._
import akka.util.Helpers._
import akka.{ actor ⇒ a }

import scala.util.control.NonFatal

abstract class EventsourcedRecoveringEvents[Command, Event, State](
  val context:                 ActorContext[Any],
  val internalStash:           StashBuffer[Any],
  val initialState:            State,
  recovery:                    Recovery,
  private var _lastSequenceNr: Long,
  writerIdentity:              WriterIdentity
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement {

  import Behaviors.same
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system.toUntyped, this)

  // -------- initialize --------
  startRecoveryTimer()

  replayEvents(lastSequenceNr + 1L, recovery.toSequenceNr)
  // ---- end of initialize ----

  val commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  // ----------

  private[this] var state: S = initialState

  // ----------

  def lastSequenceNr: Long = _lastSequenceNr
  def snapshotSequenceNr: Long = lastSequenceNr

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > _lastSequenceNr) _lastSequenceNr = persistent.sequenceNr

  private def setLastSequenceNr(value: Long): Unit =
    _lastSequenceNr = value

  // ----------

  // FIXME it's a bit of a pain to have those lazy vals, change everything to constructor parameters
  lazy val timeout = extension.journalConfigFor(journalPluginId).getMillisDuration("recovery-event-timeout")

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"
  private def startRecoveryTimer(): Unit = timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), timeout)
  private def cancelRecoveryTimer(): Unit = timers.cancel(RecoveryTickTimerKey)

  private var eventSeenInInterval = false

  def onCommand(cmd: Command): Behavior[Any] = {
    // during recovery, stash all incoming commands
    stash(cmd, same)
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[Any] = try {
    response match {
      case ReplayedMessage(repr) ⇒
        eventSeenInInterval = true
        updateLastSequenceNr(repr)
        // TODO we need some state adapters here?
        val newState = eventHandler(state, repr.payload.asInstanceOf[Event])
        state = newState
        same

      case RecoverySuccess(highestSeqNr) ⇒
        log.debug("Recovery successful, recovered until sequenceNr: {}", highestSeqNr)
        cancelRecoveryTimer()
        setLastSequenceNr(highestSeqNr)

        try onRecoveryCompleted(state)
        catch { case NonFatal(ex) ⇒ onRecoveryFailure(ex, Some(state)) }

      case ReplayMessagesFailure(cause) ⇒
        onRecoveryFailure(cause, event = None)

      case other ⇒
        stash(other, same)
    }
  } catch {
    case NonFatal(e) ⇒
      cancelRecoveryTimer()
      onRecoveryFailure(e, None)
  }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[Any] = {
    log.warning("Unexpected [{}] from SnapshotStore, already in recovering events state.", Logging.simpleName(response))
    same // ignore
  }

  /**
   * Called whenever a message replay fails. By default it logs the error.
   *
   * The actor is always stopped after this method has been invoked.
   *
   * @param cause failure cause.
   * @param event the event that was processed in `receiveRecover`, if the exception was thrown there
   */
  protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[Any] = {
    returnRecoveryPermit("on recovery failure: " + cause.getMessage)
    cancelRecoveryTimer()

    event match {
      case Some(evt) ⇒
        log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}].", evt.getClass.getName, lastSequenceNr, persistenceId)
        Behaviors.stopped

      case None ⇒
        log.error(cause, "Persistence failure when replaying events for persistenceId [{}]. " +
          "Last known sequence number [{}]", persistenceId, lastSequenceNr)
        Behaviors.stopped
    }
  }

  protected def onRecoveryCompleted(state: State): Behavior[Any] = {
    try {
      returnRecoveryPermit("finally in on recovery completed")
      recoveryCompleted(commandContext, state)

      val b = this
      val running = new EventsourcedRunning[Command, Event, State](context, internalStash, _lastSequenceNr, writerIdentity) {
        override def timers: TimerScheduler[Any] = b.timers
        override def persistenceId: String = b.persistenceId
        override def initialState: State = state
        override def commandHandler: CommandHandler[Command, Event, State] = b.commandHandler
        override def eventHandler: (State, Event) ⇒ State = b.eventHandler
        override def recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = b.recoveryCompleted
        override def snapshotWhen: (State, Event, SeqNr) ⇒ Boolean = b.snapshotWhen
        override def tagger: Event ⇒ Set[String] = b.tagger
        override def journalPluginId: String = b.journalPluginId
        override def snapshotPluginId: String = b.snapshotPluginId
      }

      tryUnstash(context, running)
    } finally {
      cancelRecoveryTimer()
    }
  }

  // FIXME separate the waiting for snapshot state from the waiting for events one // EventsourcedSnapshotRecovery -> EventsourcedRecovery
  protected def onRecoveryTick(snapshot: Boolean): Behavior[Any] =
    if (!snapshot) {
      if (!eventSeenInInterval) {
        cancelRecoveryTimer()
        val msg = s"Recovery timed out, didn't get event within $timeout, highest sequence number seen $lastSequenceNr"
        onRecoveryFailure(new RecoveryTimedOut(msg), event = None) // TODO allow users to hook into this?
      } else {
        eventSeenInInterval = false
        same
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behavior.unhandled
    }

  // ----------

  override def onMessage(msg: Any): Behavior[Any] = {
    msg match {
      // TODO explore crazy hashcode hack to make this match quicker...?
      case JournalResponse(r)          ⇒ onJournalResponse(r)
      case RecoveryTickEvent(snapshot) ⇒ onRecoveryTick(snapshot = snapshot)
      case SnapshotterResponse(r)      ⇒ onSnapshotterResponse(r)
      case c: Command @unchecked       ⇒ onCommand(c.asInstanceOf[Command]) // explicit cast to fail eagerly
    }
  }

  // ----------

  // ---------- journal interactions ---------

  private def replayEvents(fromSeqNr: SeqNr, toSeqNr: SeqNr): Behavior[Any] = {
    log.info("START REPLAY EVENTS: " + ReplayMessages(fromSeqNr, toSeqNr, recovery.replayMax, persistenceId, selfUntypedAdapted))
    journal ! ReplayMessages(fromSeqNr, toSeqNr, recovery.replayMax, persistenceId, selfUntypedAdapted)
    same // FIXME this should become another Behaviour
  }

  private def returnRecoveryPermit(reason: String): Unit = {
    log.info("returning permit... Reason: " + reason)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRecoveringEvents($persistenceId)"

}
