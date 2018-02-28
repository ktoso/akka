/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity
import akka.persistence.typed.scaladsl.PersistentBehaviors.CommandHandler

/** INTERNAL API */
@InternalApi
private[akka] class EventsourcedRequestingRecoveryPermit[Command, Event, State](
  override val context:  ActorContext[Any],
  override val timers:   TimerScheduler[Any],
  val persistenceId:     String,
  val initialState:      State,
  val commandHandler:    CommandHandler[Command, Event, State],
  val eventHandler:      (State, Event) ⇒ State,
  val recoveryCompleted: (ActorContext[Command], State) ⇒ Unit,
  val tagger:            Event ⇒ Set[String],
  val journalPluginId:   String,
  val snapshotPluginId:  String,
  val snapshotWhen:      (State, Event, Long) ⇒ Boolean,
  val recovery:          Recovery
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement {

  import akka.actor.typed.scaladsl.adapter._

  // has to be lazy, since we want to obtain the persistenceId
  protected lazy val log = Logging(context.system.toUntyped, this)

  override protected val internalStash: StashBuffer[Any] = {
    val stashSize = context.system.settings.config
      .getInt("akka.persistence.typed.stash-buffer-size")
    StashBuffer[Any](stashSize)
  }

  // --- initialization ---
  // only once we have a permit, we can become active:
  requestRecoveryPermit()

  val writerIdentity: WriterIdentity = WriterIdentity.newIdentity()

  // --- end of initialization ---

  // ----------

  def becomeRecovering(): Behavior[Any] = {
    log.info(s"[{}][{}] Becoming recovering SNAPSHOT: {}", persistenceId, context.self.path.name, recovery)
    val b = this
    new EventsourcedRecoveringSnapshot[Command, Event, State](context, internalStash, recovery, writerIdentity) {
      override def timers = b.timers

      override def persistenceId = b.persistenceId
      override def initialState = b.initialState
      override def commandHandler = b.commandHandler
      override def eventHandler = b.eventHandler
      override def recoveryCompleted = b.recoveryCompleted
      override def snapshotWhen = b.snapshotWhen
      override def tagger = b.tagger
      override def journalPluginId = b.journalPluginId
      override def snapshotPluginId = b.snapshotPluginId
    }
  }

  // ----------

  override def onMessage(msg: Any): Behavior[Any] = {
    log.info("INITIALIZING onMessage: " + msg)

    msg match {
      case RecoveryPermitter.RecoveryPermitGranted ⇒
        log.info("INIT, finished got: RecoveryPermitGranted")
        becomeRecovering()

      case other ⇒
        stash(other)
        Behaviors.same
    }
  }

  // ---------- journal interactions ---------

  private def requestRecoveryPermit(): Unit = {
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRequestingRecoveryPermit($persistenceId)"
}
