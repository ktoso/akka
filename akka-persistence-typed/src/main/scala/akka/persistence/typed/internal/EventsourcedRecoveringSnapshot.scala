/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.event.Logging
import akka.persistence.SnapshotProtocol.{ LoadSnapshot, LoadSnapshotFailed, LoadSnapshotResult }
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity
import akka.persistence.typed.scaladsl.PersistentBehaviors._
import akka.util.Helpers._
import akka.{ actor ⇒ a }

import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

abstract class EventsourcedRecoveringSnapshot[Command, Event, State](
  val context:       ActorContext[Any],
  val internalStash: StashBuffer[Any],
  recovery:          Recovery,
  writerIdentity:    WriterIdentity
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement {

  import Behaviors.same
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system.toUntyped, this)

  // -------- initialize --------
  startRecoveryTimer()

  loadSnapshot(persistenceId, recovery.fromSnapshot, recovery.toSequenceNr)
  // ---- end of initialize ----

  val commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  // ----------

  protected var awaitingSnapshot: Boolean = true

  // ----------

  private var lastSequenceNr: Long = 0L
  def snapshotSequenceNr: Long = lastSequenceNr

  // ----------

  lazy val timeout = extension.journalConfigFor(journalPluginId).getMillisDuration("recovery-event-timeout")

  // protect against snapshot stalling forever because of journal overloaded and such
  private val RecoveryTickTimerKey = "recovery-tick"
  private def startRecoveryTimer(): Unit = {
    timers.startPeriodicTimer(RecoveryTickTimerKey, RecoveryTickEvent(snapshot = false), timeout)
  }
  private def cancelRecoveryTimer(): Unit = timers.cancel(RecoveryTickTimerKey)

  def onCommand(cmd: Command): Behavior[Any] = {
    // during recovery, stash all incoming commands
    stash(cmd, Behavior.same)
  }

  def onJournalResponse(response: JournalProtocol.Response): Behavior[Any] = try {
    throw new Exception("Should not talk to journal yet! But got: " + response)
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(cause)
      throw cause
  }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[Any] = try {
    response match {
      case LoadSnapshotResult(sso, toSnr) ⇒
        var state: S = initialState
        val re: Try[SeqNr] = Try {
          sso match {
            case Some(SelectedSnapshot(metadata, snapshot)) ⇒
              state = snapshot.asInstanceOf[State]
              metadata.sequenceNr

            case None ⇒
              0 // from the start please
          }
        }

        re match {
          case Success(seqNr) ⇒
            lastSequenceNr = seqNr
            replayMessages(state, toSnr)

          case Failure(cause) ⇒
            // FIXME better exception type
            val ex = new RuntimeException(s"Failed to recover state for [$persistenceId] from snapshot offer.", cause)
            onRecoveryFailure(ex, event = None) // FIXME the failure logs has bad messages... FIXME
        }

      case LoadSnapshotFailed(cause) ⇒
        cancelRecoveryTimer()

        onRecoveryFailure(cause, event = None)

      case other ⇒
        stash(other, same)
    }
  } catch {
    case NonFatal(cause) ⇒
      returnRecoveryPermitOnlyOnFailure(cause)
      throw cause
  }

  private def replayMessages(state: State, toSnr: SeqNr): Behavior[Any] = {
    cancelRecoveryTimer()

    val b = this
    val rec = recovery.copy(toSequenceNr = toSnr, fromSnapshot = SnapshotSelectionCriteria.None) // TODO introduce new types

    new EventsourcedRecoveringEvents[Command, Event, State](context, internalStash, state, rec, lastSequenceNr, writerIdentity) {
      override def timers: TimerScheduler[Any] = b.timers
      override def persistenceId: String = b.persistenceId
      override def commandHandler: CommandHandler[Command, Event, State] = b.commandHandler
      override def eventHandler: (State, Event) ⇒ State = b.eventHandler
      override def recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = b.recoveryCompleted
      override def snapshotWhen: (State, Event, SeqNr) ⇒ Boolean = b.snapshotWhen
      override def tagger: Event ⇒ Set[String] = b.tagger
      override def journalPluginId: String = b.journalPluginId
      override def snapshotPluginId: String = b.snapshotPluginId
    }
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
    cancelRecoveryTimer()
    event match {
      case Some(evt) ⇒
        log.error(cause, "Exception in receiveRecover when replaying event type [{}] with sequence number [{}] for " +
          "persistenceId [{}].", evt.getClass.getName, lastSequenceNr, persistenceId)
        Behaviors.stopped

      case None ⇒
        log.error(cause, "Persistence failure when replaying events for persistenceId [{}]. " +
          "Last known sequence number [{}]", persistenceId, lastSequenceNr)
        Behaviors.stopped
    }
  }

  // FIXME separate the waiting for snapshot state from the waiting for events one // EventsourcedSnapshotRecovery -> EventsourcedRecovery
  protected def onRecoveryTick(snapshot: Boolean): Behavior[Any] =
    // we know we're in snapshotting mode
    if (snapshot) onRecoveryFailure(new RecoveryTimedOut(s"Recovery timed out, didn't get snapshot within $timeout"), event = None)
    else same // ignore, since we received the snapshot already

  // ----------

  override def onMessage(msg: Any): Behavior[Any] = {
    log.info(s"[${Logging.simpleName(getClass)}] onMessage: " + msg)
    msg match {
      // TODO explore crazy hashcode hack to make this match quicker...?
      case SnapshotterResponse(r)      ⇒ onSnapshotterResponse(r)
      case JournalResponse(r)          ⇒ onJournalResponse(r)
      case RecoveryTickEvent(snapshot) ⇒ onRecoveryTick(snapshot = snapshot)
      case c: Command @unchecked       ⇒ onCommand(c.asInstanceOf[Command]) // explicit cast to fail eagerly
    }
  }

  // ----------

  // ---------- journal interactions ---------

  /**
   * Instructs the snapshot store to load the specified snapshot and send it via an [[SnapshotOffer]]
   * to the running [[PersistentActor]].
   */
  private def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long): Unit = {
    snapshotStore.tell(LoadSnapshot(persistenceId, criteria, toSequenceNr), selfUntypedAdapted)
  }

  private def returnRecoveryPermitOnlyOnFailure(cause: Throwable): Unit = {
    log.debug("Returning recovery permit, on failure because: " + cause.getMessage)
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.ReturnRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRecoveringSnapshot($persistenceId)"

}
