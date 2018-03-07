package akka.persistence.typed.internal

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.internal.TimerSchedulerImpl
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.{ EventsourcedProtocol, WriterIdentity }
import akka.persistence.typed.internal.EventsourcedBehavior.EventsourcedProtocol
import akka.persistence.typed.scaladsl.PersistentBehavior
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler
import akka.util.ConstantFun

/** INTERNAL API */
@InternalApi
private[persistence] case class EventsourcedSetup[Command, Event, State](
  persistenceId:  String,
  initialState:   State,
  commandHandler: PersistentBehaviors.CommandHandler[Command, Event, State],
  eventHandler:   (State, Event) ⇒ State,

  writerIdentity:    WriterIdentity                        = WriterIdentity.newIdentity(),
  recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = ConstantFun.scalaAnyTwoToUnit,
  tagger:            Event ⇒ Set[String]                   = (_: Event) ⇒ Set.empty[String],
  journalPluginId:   String                                = "",
  snapshotPluginId:  String                                = "",
  snapshotWhen:      (State, Event, Long) ⇒ Boolean        = ConstantFun.scalaAnyThreeToFalse,
  recovery:          Recovery                              = Recovery()
) extends PersistentBehavior[Command, Event, State] {

  override def apply(ctx: typed.ActorContext[Command]): Behavior[Command] =
    Behaviors.setup[EventsourcedProtocol] { ctx ⇒
      EventsourcedBehavior.withMDC(persistenceId, EventsourcedBehavior.PhaseName.AwaitPermit) {
        Behaviors.withTimers[EventsourcedProtocol] { timers ⇒
          new EventsourcedRequestingRecoveryPermit(
            this,
            ctx,
            timers
          )
        }
      }
    }.widen[Any] {
      case res: JournalProtocol.Response           ⇒ EventsourcedProtocol.JournalResponse(res)
      case RecoveryPermitter.RecoveryPermitGranted ⇒ EventsourcedProtocol.RecoveryPermitGranted
      case res: SnapshotProtocol.Response          ⇒ EventsourcedProtocol.SnapshotterResponse(res)
      case cmd: Command @unchecked                 ⇒ EventsourcedProtocol.IncomingCommand(cmd)
    }.narrow[Command]

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): PersistentBehavior[Command, Event, State] =
    copy(recoveryCompleted = callback)

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): PersistentBehavior[Command, Event, State] =
    copy(snapshotWhen = predicate)

  /**
   * Snapshot every N events
   *
   * `numberOfEvents` should be greater than 0
   */
  def snapshotEvery(numberOfEvents: Long): PersistentBehavior[Command, Event, State] = {
    require(numberOfEvents > 0, s"numberOfEvents should be positive: Was $numberOfEvents")
    copy(snapshotWhen = (_, _, seqNr) ⇒ seqNr % numberOfEvents == 0)
  }

  /**
   * Change the journal plugin id that this actor should use.
   */
  def withPersistencePluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "persistence plugin id must not be null; use empty string for 'default' journal")
    copy(journalPluginId = id)
  }

  /**
   * Change the snapshot store plugin id that this actor should use.
   */
  def withSnapshotPluginId(id: String): PersistentBehavior[Command, Event, State] = {
    require(id != null, "snapshot plugin id must not be null; use empty string for 'default' snapshot store")
    copy(snapshotPluginId = id)
  }

  /**
   * Changes the snapshot selection criteria used by this behavior.
   * By default the most recent snapshot is used, and the remaining state updates are recovered by replaying events
   * from the sequence number up until which the snapshot reached.
   *
   * You may configure the behavior to skip recovering snapshots completely, in which case the recovery will be
   * performed by replaying all events -- which may take a long time.
   */
  def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): PersistentBehavior[Command, Event, State] = {
    copy(recovery = Recovery(selection))
  }

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State] =
    copy(tagger = tagger)

  def copy(
    initialState:      State                                 = initialState,
    commandHandler:    CommandHandler[Command, Event, State] = commandHandler,
    eventHandler:      (State, Event) ⇒ State                = eventHandler,
    recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = recoveryCompleted,
    tagger:            Event ⇒ Set[String]                   = tagger,
    snapshotWhen:      (State, Event, Long) ⇒ Boolean        = snapshotWhen,
    journalPluginId:   String                                = journalPluginId,
    snapshotPluginId:  String                                = snapshotPluginId,
    recovery:          Recovery                              = recovery): EventsourcedSetup[Command, Event, State] =
    new EventsourcedSetup[Command, Event, State](
      persistenceId = persistenceId,
      initialState = initialState,
      commandHandler = commandHandler,
      eventHandler = eventHandler,
      recoveryCompleted = recoveryCompleted,
      tagger = tagger,
      journalPluginId = journalPluginId,
      snapshotPluginId = snapshotPluginId,
      snapshotWhen = snapshotWhen,
      recovery = recovery)

}

