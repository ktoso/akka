/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import scala.collection.immutable
import scala.collection.JavaConverters._

import akka.persistence._
import akka.persistence.journal.{ SyncWriteJournal ⇒ ScalaSyncWriteJournal }

/**
 * Java API: abstract journal, optimized for synchronous writes.
 */
abstract class SyncWriteJournal extends AsyncRecovery with ScalaSyncWriteJournal with SyncWritePlugin {
  final def writeMessages(messages: immutable.Seq[PersistentRepr]) =
    doWriteMessages(messages.asJava)

  final def deleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean) =
    doDeleteMessagesTo(persistenceId, toSequenceNr, permanent)
}
