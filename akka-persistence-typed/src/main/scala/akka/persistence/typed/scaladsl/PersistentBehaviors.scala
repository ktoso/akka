/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.scaladsl

import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.internal.TimerSchedulerImpl
import akka.{ actor ⇒ a }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.persistence.typed.internal.EventsourcedRequestingRecoveryPermit
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

import scala.collection.{ immutable ⇒ im }
import scala.language.implicitConversions

object PersistentBehaviors {

  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] = {
    new PersistentBehavior(
      persistenceId,
      initialState,
      commandHandler,
      eventHandler,
      recoveryCompleted = (_: ActorContext[Command], _: State) ⇒ (),
      tagger = (_: Event) ⇒ Set.empty,
      snapshotWhen = (_: State, _: Event, _: Long) ⇒ false,
      journalPluginId = "",
      snapshotPluginId = ""
    )

  /**
   * Create a `Behavior` for a persistent actor in Cluster Sharding, when the persistenceId is not known
   * until the actor is started and typically based on the entityId, which
   * is the actor name.
   *
   * TODO This will not be needed when it can be wrapped in `Actor.deferred`.
   */
  @Deprecated // FIXME remove this
  def persistentEntity[Command, Event, State](
    persistenceIdFromActorName: String ⇒ String,
    initialState:               State,
    commandHandler:             CommandHandler[Command, Event, State],
    eventHandler:               (State, Event) ⇒ State): PersistentBehavior[Command, Event, State] =
    ???

  /**
   * Factories for effects - how a persistent actor reacts on a command
   */
  object Effect {

    // TODO docs
    def persist[Event, State](event: Event): Effect[Event, State] =
      Persist(event)

    // TODO docs
    def persist[Event, A <: Event, B <: Event, State](evt1: A, evt2: B, events: Event*): Effect[Event, State] =
      persist(evt1 :: evt2 :: events.toList)

    // TODO docs
    def persist[Event, State](eventOpt: Option[Event]): Effect[Event, State] =
      eventOpt match {
        case Some(evt) ⇒ persist[Event, State](evt)
        case _         ⇒ none[Event, State]
      }

    // TODO docs
    def persist[Event, State](events: im.Seq[Event]): Effect[Event, State] =
      PersistAll(events)

    // TODO docs
    def persist[Event, State](events: im.Seq[Event], sideEffects: im.Seq[ChainableEffect[Event, State]]): Effect[Event, State] =
      new CompositeEffect[Event, State](PersistAll[Event, State](events), sideEffects)

    /**
     * Do not persist anything
     */
    def none[Event, State]: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

    /**
     * This command is not handled, but it is not an error that it isn't.
     */
    def unhandled[Event, State]: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

    /**
     * Stop this persistent actor
     */
    def stop[Event, State]: ChainableEffect[Event, State] = Stop.asInstanceOf[ChainableEffect[Event, State]]
  }

  /**
   * Instances are created through the factories in the [[Effect]] companion object.
   *
   * Not for user extension.
   */
  @DoNotInherit
  trait Effect[+Event, State] {
    self: EffectImpl[Event, State] ⇒
    /* All events that will be persisted in this effect */
    def events: im.Seq[Event]

    def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]]

    /** Convenience method to register a side effect with just a callback function */
    final def andThen(callback: State ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State](callback))

    /** Convenience method to register a side effect with just a lazy expression */
    final def andThen(callback: ⇒ Unit): Effect[Event, State] =
      CompositeEffect(this, SideEffect[Event, State]((_: State) ⇒ callback))

    /** The side effect is to stop the actor */
    def andThenStop: Effect[Event, State] =
      CompositeEffect(this, Effect.stop[Event, State])
  }

  @InternalApi
  private[akka] object CompositeEffect {
    def apply[Event, State](effect: Effect[Event, State], sideEffects: ChainableEffect[Event, State]): Effect[Event, State] =
      if (effect.events.isEmpty) {
        CompositeEffect[Event, State](
          None,
          effect.sideEffects ++ (sideEffects :: Nil)
        )
      } else {
        CompositeEffect[Event, State](
          Some(effect),
          sideEffects :: Nil
        )
      }
  }

  @InternalApi
  private[akka] final case class CompositeEffect[Event, State](
    persistingEffect: Option[Effect[Event, State]],
    _sideEffects:     im.Seq[ChainableEffect[Event, State]]) extends Effect[Event, State] {
    override val events = persistingEffect.map(_.events).getOrElse(Nil)

    override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = _sideEffects.asInstanceOf[im.Seq[ChainableEffect[E, State]]]

  }

  @InternalApi
  private[akka] case object PersistNothing extends Effect[Nothing, Nothing]

  @InternalApi
  private[akka] case class Persist[Event, State](event: Event) extends Effect[Event, State] {
    override def events = event :: Nil
  }
  @InternalApi
  private[akka] case class PersistAll[Event, State](override val events: im.Seq[Event]) extends Effect[Event, State]

  /**
   * Not for user extension
   */
  @DoNotInherit
  abstract class ChainableEffect[Event, State] extends EffectImpl[Event, State]

  type CommandHandler[Command, Event, State] = (ActorContext[Command], State, Command) ⇒ Effect[Event, State]

  /**
   * The `CommandHandler` defines how to act on commands.
   *
   * Note that you can have different command handlers based on current state by using
   * [[CommandHandler#byState]].
   */
  object CommandHandler {

    /**
     * Convenience for simple commands that don't need the state and context.
     *
     * @see [[Effect]] for possible effects of a command.
     */
    def command[Command, Event, State](commandHandler: Command ⇒ Effect[Event, State]): CommandHandler[Command, Event, State] =
      (_, _, cmd) ⇒ commandHandler(cmd)

    /**
     * Select different command handlers based on current state.
     */
    def byState[Command, Event, State](choice: State ⇒ CommandHandler[Command, Event, State]): CommandHandler[Command, Event, State] =
      new ByStateCommandHandler(choice)

  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final class ByStateCommandHandler[Command, Event, State](
    choice: State ⇒ CommandHandler[Command, Event, State])
    extends CommandHandler[Command, Event, State] {

    override def apply(ctx: ActorContext[Command], state: State, cmd: Command): Effect[Event, State] =
      choice(state)(ctx, state, cmd)

  }
}

final class PersistentBehavior[Command, Event, State](
  _persistenceId:        String,
  val initialState:      State,
  val commandHandler:    PersistentBehaviors.CommandHandler[Command, Event, State],
  val eventHandler:      (State, Event) ⇒ State,
  val recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  val tagger:            Event ⇒ Set[String],
  val journalPluginId:   String,
  val snapshotPluginId:  String,
  val snapshotWhen:      (State, Event, Long) ⇒ Boolean
) extends DeferredBehavior[Command](ctx ⇒
  TimerSchedulerImpl.wrapWithTimers[Command](timers ⇒
    new InitialPersistentBehavior(
      ctx.asInstanceOf[ActorContext[Any]], // sorry
      timers.asInstanceOf[TimerScheduler[Any]],
      _persistenceId,
      initialState,
      commandHandler,
      eventHandler,
      recoveryCompleted,
      tagger,
      journalPluginId,
      snapshotPluginId,
      snapshotWhen
    ).asInstanceOf[Behavior[Command]] // FIXME this is nasty, but works since we adapt other things

  )(ctx)) {

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
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def withTagger(tagger: Event ⇒ Set[String]): PersistentBehavior[Command, Event, State] =
    copy(tagger = tagger)

  private def copy(
    initialState:      State                                 = initialState,
    commandHandler:    CommandHandler[Command, Event, State] = commandHandler,
    eventHandler:      (State, Event) ⇒ State                = eventHandler,
    recoveryCompleted: (ActorContext[Command], State) ⇒ Unit = recoveryCompleted,
    tagger:            Event ⇒ Set[String]                   = tagger,
    snapshotWhen:      (State, Event, Long) ⇒ Boolean        = snapshotWhen,
    journalPluginId:   String                                = journalPluginId,
    snapshotPluginId:  String                                = snapshotPluginId): PersistentBehavior[Command, Event, State] =
    new PersistentBehavior[Command, Event, State](
      _persistenceId = _persistenceId,
      initialState = initialState,
      commandHandler = commandHandler,
      eventHandler = eventHandler,
      recoveryCompleted = recoveryCompleted,
      tagger = tagger,
      journalPluginId = journalPluginId,
      snapshotPluginId = snapshotPluginId,
      snapshotWhen = snapshotWhen)

}

// this is only convenience as it's a pain to create the abstract one directly in the immutable factory method
final class InitialPersistentBehavior[Command, Event, State](
  override val context:  ActorContext[Any],
  override val timers:   TimerScheduler[Any],
  val persistenceId:     String,
  val initialState:      State,
  val commandHandler:    PersistentBehaviors.CommandHandler[Command, Event, State],
  val eventHandler:      (State, Event) ⇒ State,
  val recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  val tagger:            Event ⇒ Set[String],
  val journalPluginId:   String,
  val snapshotPluginId:  String,
  val snapshotWhen:      (State, Event, Long) ⇒ Boolean
) extends EventsourcedRequestingRecoveryPermit[Command, Event, State](context)
