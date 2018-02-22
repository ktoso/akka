/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.StoppedBehavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer }
import akka.event.Logging
import akka.persistence.Eventsourced.{ PendingHandlerInvocation, StashingHandlerInvocation }
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.journal.Tagged
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity
import akka.persistence.typed.scaladsl.PersistentBehaviors._
import akka.{ actor ⇒ a }

import scala.annotation.tailrec
import scala.collection.immutable

abstract class EventsourcedRunning[Command, Event, State](
  val context:            ActorContext[Any],
  val internalStash:      StashBuffer[Any],
  private var sequenceNr: Long,
  writerIdentity:         WriterIdentity
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement { same ⇒

  import Behaviors.same
  import EventsourcedBehavior._
  import akka.actor.typed.scaladsl.adapter._

  protected val log = Logging(context.system.toUntyped, this)

  private def commandContext: ActorContext[Command] = context.asInstanceOf[ActorContext[Command]]

  // ----------

  private[this] var state: S = initialState

  // Holds callbacks for persist calls (note that we do not implement persistAsync currently)
  private def hasNoPendingInvocations: Boolean = pendingInvocations.isEmpty
  private val pendingInvocations = new java.util.LinkedList[PendingHandlerInvocation]() // we only append / isEmpty / get(0) on it
  private var eventBatch: List[PersistentEnvelope] = Nil

  private var journalBatch = Vector.empty[PersistentEnvelope]
  private var writeInProgress = false

  // ----------

  private def lastSequenceNr: Long = sequenceNr
  private def snapshotSequenceNr: Long = lastSequenceNr

  private def updateLastSequenceNr(persistent: PersistentRepr): Unit =
    if (persistent.sequenceNr > lastSequenceNr) sequenceNr = persistent.sequenceNr
  private def nextSequenceNr(): Long = {
    sequenceNr += 1L
    sequenceNr
  }
  // ----------

  private def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[Any] = {
    response match {
      case SaveSnapshotSuccess(meta)     ⇒ log.info("Save snapshot successful: " + meta)
      case SaveSnapshotFailure(meta, ex) ⇒ log.error(ex, "Save snapshot failed! " + meta) // FIXME no fail? no callback?
    }
    same
  }

  // ----------

  trait EventsourcedRunningPhase {
    def name: String
    def onCommand(c: Command): Behavior[Any]
    def onJournalResponse(response: JournalProtocol.Response): Behavior[Any]
  }

  object HandlingCommands extends EventsourcedRunningPhase {
    def name = "HandlingCommands"

    final override def onCommand(command: Command): Behavior[Any] = {
      val effect = commandHandler(commandContext, state, command)
      applyEffects(command, effect)
    }
    final override def onJournalResponse(response: Response): Behavior[Any] = {
      // should not happen, what would it reply?
      throw new RuntimeException("Received message which should not happen in Running state!")
    }
  }

  object PersistingEvents extends EventsourcedRunningPhase {
    def name = "PersistingEvents"
    final override def onCommand(c: Command): Behavior[Any] = {
      log.info(s"PERSISTING EVENTS, command, STASH: ${c}")
      stash(c, same)
    }
    final override def onJournalResponse(response: Response): Behavior[Any] = {
      log.info("RESPONSE == " + response)
      response match {
        case WriteMessageSuccess(p, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case we ignore the call to the handler
          if (id == writerIdentity.instanceId) {
            updateLastSequenceNr(p)
            popApplyHandler(p.payload)
            onWriteMessageComplete()
          } else same

        case WriteMessageRejected(p, cause, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case the handler has already been discarded
          if (id == writerIdentity.instanceId) {
            updateLastSequenceNr(p)
            onWriteMessageComplete() // FIXME no sure if we should call it here
            onPersistRejected(cause, p.payload, p.sequenceNr)
          } else same

        case WriteMessageFailure(p, cause, id) ⇒
          // instanceId mismatch can happen for persistAsync and defer in case of actor restart
          // while message is in flight, in that case the handler has already been discarded
          if (id == writerIdentity.instanceId) {
            onWriteMessageComplete()
            onPersistFailureThenStop(cause, p.payload, p.sequenceNr)
          } else same

        case _: LoopMessageSuccess ⇒
          // ignore, not used in Typed Persistence (needed for persistAsync)
          same

        case WriteMessagesSuccessful ⇒
          writeInProgress = false
          flushJournalBatch()

        case WriteMessagesFailed(_) ⇒
          writeInProgress = false
          same // it will be stopped by the first WriteMessageFailure message

      }
    }

    private def onWriteMessageComplete(): Behavior[Any] = {
      tryBecomeHandlingCommands()
      tryUnstash(context, same)
    }

    private def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Behavior[Any] = {
      log.error(
        cause,
        "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
        event.getClass.getName, seqNr, persistenceId, cause.getMessage)

      same
    }

    private def onPersistFailureThenStop(cause: Throwable, event: Any, seqNr: Long): Behavior[Any] = {
      log.error(cause, "Failed to persist event type [{}] with sequence number [{}] for persistenceId [{}].",
        event.getClass.getName, seqNr, persistenceId)

      // FIXME should we throw perhaps instead?

      Behaviors.stopped
    }

  }

  // the active phase switches between PersistingEvents and HandlingCommands;
  // we do this via a var instead of behaviours to keep allocations down as this will be flip/flaping on every Persist effect
  private[this] var phase: EventsourcedRunningPhase = HandlingCommands

  override def onMessage(msg: Any): Behavior[Any] = {
    log.info("RUNNING onMessage: " + msg + s" @ ${phase.name}")
    msg match {
      // TODO explore crazy hashcode hack to make this match quicker...?
      case SnapshotterResponse(r) ⇒ onSnapshotterResponse(r)
      case JournalResponse(r)     ⇒ phase.onJournalResponse(r)
      case command: Command @unchecked ⇒
        // the above type-check does nothing, since Command is tun
        // we cast explicitly to fail early in case of type mismatch
        val c = command.asInstanceOf[Command]
        phase.onCommand(c)
    }
  }

  // ----------

  def applySideEffects(effects: immutable.Seq[ChainableEffect[_, S]]): Behavior[Any] = {
    var res: Behavior[Any] = same
    val it = effects.iterator

    // if at least one effect results in a `stop`, we need to stop
    // manual loop implementation to avoid allocations and multiple scans
    while (it.hasNext) {
      val effect = it.next()
      applySideEffect(effect) match {
        case _: StoppedBehavior[_] ⇒ res = Behaviors.stopped
        case _                     ⇒ // nothing to do
      }
    }

    res
  }

  def applySideEffect(effect: ChainableEffect[_, S]): Behavior[Any] = effect match {
    case _: Stop.type @unchecked ⇒
      Behaviors.stopped

    case SideEffect(callbacks) ⇒
      callbacks(state)
      same
  }

  def applyEvent(s: S, event: E): S =
    eventHandler(s, event)

  private def applyEffects(msg: Any, effect: Effect[E, S], sideEffects: immutable.Seq[ChainableEffect[_, S]] = Nil): Behavior[Any] = {
    log.info(s"ApplyEffects: ${msg} => Effect: ${effect}")

    effect match {
      case CompositeEffect(e, currentSideEffects) ⇒
        applyEffects(msg, e, currentSideEffects ++ sideEffects) unlessStopped tryUnstash(context, this)
      //      case CompositeEffect(effect, currentSideEffects) ⇒
      //        applyEffects(msg, effect, currentSideEffects ++ sideEffects) // "unwrap" & recur
      //      case CompositeEffect(_, currentSideEffects) ⇒
      //        applySideEffects(currentSideEffects ++ sideEffects) unlessStopped tryUnstash(context, this)

      case Persist(event) ⇒
        // apply the event before persist so that validation exception is handled before persisting
        // the invalid event, in case such validation is implemented in the event handler.
        // also, ensure that there is an event handler for each single event
        state = applyEvent(state, event)
        val tags = tagger(event)
        val eventToPersist = if (tags.isEmpty) event else Tagged(event, tags)

        log.info(s" >>>> [${context.self.path.name}] in Persist($event) @ ${lastSequenceNr}")

        internalPersist(eventToPersist) { _ ⇒
          applySideEffects(sideEffects)

          log.info(s" >>>> [${context.self.path.name}] in Persist($event) CALLBACK @ ${lastSequenceNr}")

          log.info(s"persist callback [${context.self.path.name}] == seqNr=${lastSequenceNr} => ${snapshotWhen(state, event, lastSequenceNr)}")
          if (snapshotWhen(state, event, lastSequenceNr))
            internalSaveSnapshot(state)
        }

      case PersistAll(events) ⇒
        if (events.nonEmpty) {
          // apply the event before persist so that validation exception is handled before persisting
          // the invalid event, in case such validation is implemented in the event handler.
          // also, ensure that there is an event handler for each single event
          var count = events.size
          var seqNr = lastSequenceNr
          val (newState, shouldSnapshotAfterPersist) = events.foldLeft((state, false)) {
            case ((currentState, snapshot), event) ⇒
              seqNr += 1
              val shouldSnapshot = snapshot || snapshotWhen(currentState, event, seqNr)
              (applyEvent(currentState, event), shouldSnapshot)
          }
          state = newState
          val eventsToPersist = events.map { event ⇒
            val tags = tagger(event)
            if (tags.isEmpty) event else Tagged(event, tags)
          }
          internalPersistAll(eventsToPersist) { _ ⇒
            count -= 1
            if (count == 0) {
              sideEffects.foreach(applySideEffect)
              if (shouldSnapshotAfterPersist)
                internalSaveSnapshot(state)
            }
          }
        } else {
          // run side-effects even when no events are emitted
          sideEffects.foreach(applySideEffect)
          same
        }

      case _: PersistNothing.type @unchecked ⇒
        // ignore, no effects to apply
        same

      case _: Unhandled.type @unchecked ⇒
        Behavior.unhandled

      case c: ChainableEffect[_, S] ⇒
        applySideEffect(c)
    }
  }

  private def shouldNotHappen(): Behavior[Any] = {
    throw new RuntimeException("Received message which should not happen in Running state!")
  }

  private def popApplyHandler(payload: Any): Unit =
    try pendingInvocations.pop().handler(payload)
    finally flushBatch()

  private def flushBatch() {
    if (eventBatch.nonEmpty) {
      journalBatch ++= eventBatch.reverse
      eventBatch = Nil
    }

    flushJournalBatch()
  }

  private def flushJournalBatch(): Behavior[Any] = {
    if (!writeInProgress && journalBatch.nonEmpty) {
      journal ! WriteMessages(journalBatch, selfUntypedAdapted, writerIdentity.instanceId) // TODO maybe we don't need the adapting...
      journalBatch = Vector.empty
      writeInProgress = true
    }

    same
  }

  private def becomePersistingEvents(): Behavior[Any] = {
    log.info(" >>>> becomePersistingEvents <<<<")
    require(phase != PersistingEvents, "Attempted to become PersistingEvents while already in this phase! Logic error?")

    phase = PersistingEvents
    same
  }
  private def tryBecomeHandlingCommands(): Behavior[Any] = {
    require(phase != HandlingCommands, "Attempted to become HandlingCommands while already in this phase! Logic error?")

    if (hasNoPendingInvocations) {
      log.info(s" >>>> becomeHandlingCommands YES <<<<")
      phase = HandlingCommands
    } else {
      log.info(s" >>>> becomeHandlingCommands NOPE -- pending invocations: ${pendingInvocations.size()} <<<<")
    }

    same
  }

  // ---------- journal interactions ---------

  // Any since can be `E` or `Tagged`
  private def internalPersist(event: Any)(handler: Any ⇒ Unit): Behavior[Any] = {
    pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])

    val senderNotKnownBecauseAkkaTyped = null
    val repr = PersistentRepr(event, persistenceId = persistenceId, sequenceNr = nextSequenceNr(), writerUuid = writerIdentity.writerUuid, sender = senderNotKnownBecauseAkkaTyped)

    eventBatch ::= AtomicWrite(repr) // batching not used, since no persistAsync
    journal.tell(JournalProtocol.WriteMessages(eventBatch, selfUntypedAdapted, writerIdentity.instanceId), selfUntypedAdapted)
    eventBatch = Nil

    becomePersistingEvents()
  }

  private def internalPersistAll(events: immutable.Seq[Any])(handler: Any ⇒ Unit): Behavior[Any] = {
    if (events.nonEmpty) {
      val senderNotKnownBecauseAkkaTyped = null

      events.foreach { event ⇒
        pendingInvocations addLast StashingHandlerInvocation(event, handler.asInstanceOf[Any ⇒ Unit])
      }
      val write = AtomicWrite(events.map(PersistentRepr.apply(_, persistenceId = persistenceId,
        sequenceNr = nextSequenceNr(), writerUuid = writerIdentity.writerUuid, sender = senderNotKnownBecauseAkkaTyped)))

      eventBatch ::= write
      journal.tell(JournalProtocol.WriteMessages(eventBatch, selfUntypedAdapted, writerIdentity.instanceId), selfUntypedAdapted)
      eventBatch = Nil

      becomePersistingEvents()
    } else same
  }

  private def internalSaveSnapshot(snapshot: State): Unit = {
    snapshotStore.tell(SnapshotProtocol.SaveSnapshot(SnapshotMetadata(persistenceId, snapshotSequenceNr), snapshot), selfUntypedAdapted)
  }

  override def toString = s"EventsourcedRunning($persistenceId,${phase.name})"
}
