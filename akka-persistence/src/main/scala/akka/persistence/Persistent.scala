/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ List ⇒ JList }

import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.pattern.PromiseActorRef
import akka.persistence.serialization.Message

import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Marks messages which can be resequenced by the [[akka.persistence.journal.AsyncWriteJournal]].
 *
 * In essence it is either an [[NonPersistentRepr]] or [[PersistentRepr]].
 */
private[persistence] sealed trait PersistentEnvelope {
  def payload: Any
  def sender: ActorRef // TODO do not persist?
}

/**
 * INTERNAL API
 * Message which can be resequenced by the Journal, but will not be persisted.
 */
private[persistence] final case class NonPersistentRepr(payload: Any, sender: ActorRef) extends PersistentEnvelope

/**
 * Plugin API: representation of a persistent message in the journal plugin API.
 *
 * @see [[journal.SyncWriteJournal]]
 * @see [[journal.AsyncWriteJournal]]
 * @see [[journal.AsyncRecovery]]
 * @see [[journal.Tagger]]
 */
trait PersistentRepr extends PersistentEnvelope with Message {

  /**
   * This persistent message's payload.
   */
  def payload: Any

  /**
   * Persistent id that journals a persistent message
   */
  def persistenceId: String

  /**
   * This persistent message's sequence number.
   */
  def sequenceNr: Long

  /**
   * Creates a new persistent message with the specified `payload`.
   */
  def withPayload(payload: Any): PersistentRepr

  /**
   * Set by [[akka.persistence.journal.Tagger]] for the given payload
   */
  def tags: immutable.Set[String]

  /**
   * `true` if this message is marked as deleted.
   */
  def deleted: Boolean

  /**
   * Sender of this message.
   */
  def sender: ActorRef

  /**
   * INTERNAL API.
   */
  private[persistence] def prepareWrite(sender: ActorRef): PersistentRepr

  /**
   * INTERNAL API.
   */
  private[persistence] def prepareWrite()(implicit context: ActorContext): PersistentRepr =
    prepareWrite(if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender)

  /**
   * Creates a new copy of this [[PersistentRepr]].
   */
  def update(
    sequenceNr: Long = sequenceNr,
    persistenceId: String = persistenceId,
    deleted: Boolean = deleted,
    sender: ActorRef = sender,
    tags: immutable.Set[String] = tags): PersistentRepr

}

object PersistentRepr {
  /**
   * Plugin API: value of an undefined processor id.
   */
  val Undefined = ""

  /**
   * Plugin API.
   */
  def apply(
    payload: Any,
    sequenceNr: Long = 0L,
    persistenceId: String = PersistentRepr.Undefined,
    deleted: Boolean = false,
    sender: ActorRef = null): PersistentRepr =
    PersistentImpl(payload, sequenceNr, persistenceId, deleted, sender)

  /**
   * Java API, Plugin API.
   */
  def create = apply _

  /**
   * extractor of payload and sequenceNr.
   */
  def unapply(persistent: PersistentRepr): Option[(Any, Long)] =
    Some((persistent.payload, persistent.sequenceNr))
}

/**
 * INTERNAL API.
 */
private[persistence] final case class PersistentImpl(
  payload: Any,
  sequenceNr: Long,
  override val persistenceId: String,
  deleted: Boolean,
  sender: ActorRef,
  tags: immutable.Set[String] = Set.empty) extends PersistentRepr {

  override def withPayload(payload: Any): PersistentRepr =
    copy(payload = payload)

  override def prepareWrite(sender: ActorRef) =
    copy(sender = sender)

  override def update(
    sequenceNr: Long = sequenceNr,
    persistenceId: String = persistenceId,
    deleted: Boolean = deleted,
    sender: ActorRef = sender,
    tags: immutable.Set[String] = tags): PersistentRepr =
    copy(sequenceNr = sequenceNr, persistenceId = persistenceId, deleted = deleted, sender = sender, tags = tags)
}

