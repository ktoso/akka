/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal

import akka.persistence.Persistence
import akka.persistence.{ PersistentRepr, PersistentEnvelope }
import akka.actor.Actor
import scala.collection.immutable

private[akka] trait WriteJournalBase {
  this: Actor ⇒

  private val extension = Persistence(context.system)

  protected def preparePersistentBatch(rb: immutable.Seq[PersistentEnvelope]): immutable.Seq[PersistentRepr] = {
    rb.filter(persistentPrepareWrite)
      .map(tagPersistent)
      .asInstanceOf[immutable.Seq[PersistentRepr]] // filter instead of flatMap to avoid Some allocations
  }

  private def persistentPrepareWrite(r: PersistentEnvelope): Boolean = r match {
    case p: PersistentRepr ⇒
      p.prepareWrite()
      true
    case _ ⇒
      false
  }

  private def tagPersistent(r: PersistentEnvelope): PersistentEnvelope = r match {
    case p: PersistentRepr if extension.taggingEnabled ⇒
      val tagger = extension.taggerFor(p.payload)
      p.update(tags = tagger.tagsFor(p.payload))
    case p ⇒ p
  }

}
