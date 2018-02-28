/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl

import java.util.Collections

import akka.actor.typed.Behavior.UntypedPropsBehavior
import akka.actor.typed.internal.adapter.PropsAdapter
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.persistence.typed._
import akka.persistence.typed.internal._

/** Java API: Persistent Behaviour for */
@ApiMayChange
abstract class PersistentBehavior[Command, Event, State >: Null](val persistenceId: String) extends UntypedPropsBehavior[Command] {

  protected def Effect: EffectFactories[Command, Event, State] = EffectFactory.asInstanceOf[EffectFactories[Command, Event, State]]

  protected def initialState: State

  protected def commandHandler(): CommandHandler[Command, Event, State]

  protected def eventHandler(): EventHandler[Event, State]

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def commandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def byStateCommandHandlerBuilder(): ByStateCommandHandlerBuilder[Command, Event, State] =
    new ByStateCommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, builder
   */
  protected final def eventHandlerBuilder(): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(ctx: ActorContext[Command], state: State): Unit = {}

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def shouldSnapshot(state: State, event: Event, sequenceNr: Long): Boolean = false
  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(event: Event): java.util.Set[String] = Collections.emptySet()

  /** INTERNAL API */
  @InternalApi private[akka] override def untypedProps(props: akka.actor.typed.Props): akka.actor.Props = {
    val behaviorImpl = scaladsl.PersistentBehaviors.immutable[Command, Event, State](
      persistenceId,
      initialState,
      (c, state, cmd) ⇒ commandHandler()(c.asJava, state, cmd).asInstanceOf[EffectImpl[Event, State]],
      eventHandler()(_, _)
    )

    PropsAdapter(() ⇒ behaviorImpl, props)
  }

}

