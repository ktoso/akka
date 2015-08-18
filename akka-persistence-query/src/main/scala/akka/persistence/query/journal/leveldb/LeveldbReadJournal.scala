/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import java.util.Map.Entry

import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.persistence.PersistentRepr
import akka.persistence.journal.leveldb.Key._
import akka.persistence.journal.leveldb.{ Key, SharedLeveldbStore }
import akka.persistence.query.{ EventsByPersistenceId, Hint, Query, scaladsl }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.SourceShape
import akka.stream.scaladsl.{Sink, Broadcast, FlowGraph, Source}
import akka.stream.stage._
import akka.util.ByteString._
import akka.util.Timeout
import com.typesafe.config.Config
import org.iq80.leveldb.{ DBIterator, DB, ReadOptions }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object LeveldbReadJournal {
  final val Identifier = "akka.persistence.query.journal.leveldb"
}

class LeveldbReadJournal(system: ExtendedActorSystem) extends scaladsl.ReadJournal
  with LeveldbReadOps {

  val config = system.settings.config.getConfig("akka.persistence.query.journal.leveldb")
  val serialization = SerializationExtension(system)

  var leveldb: DB = _

  // TODO how can we work around this (for plugin writers)?
  private implicit class CastHelpers(val s: Source[Any, Unit]) extends AnyVal {
    def cast[T, M] = s.asInstanceOf[Source[T, M]]
  }

  override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] = q match {
    case EventsByPersistenceId(pid, from, to) ⇒ eventsByPersistenceId(pid, from, to).cast
    case unknown                              ⇒ unsupportedQueryType(unknown)
  }

  def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[Any, Unit] = {
    import Key._
    import scala.collection.JavaConverters._
    type RawElement = Entry[Array[Byte], Array[Byte]]
    type Element = PersistentRepr

    // TODO I'd like to say Source(() => stage)) without the Source.empty hack
    val persistentEvents = Source.empty
      .transform { () => LeveldbIterationTransform(leveldb, persistenceId, fromSequenceNr, toSequenceNr) }
      .map { _.payload }

    persistentEvents.named("eventsByPersistenceId")
  }

  // TODO is a "SourceStage" a pattern we could help more with?
  // TODO if we need polling it would be best implemented as an AsyncStage I think, make the polling a timer input
  case class LeveldbIterationTransform(db: DB, persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long)
    extends PushPullStage[Nothing, PersistentRepr] {

    def pid = numericId(persistenceId)

    private var ro: ReadOptions = _
    private var iter: DBIterator = _
    private var key: Key = _

    override def preStart(ctx: LifecycleContext): Unit = {
      ro = leveldbSnapshot()
      iter = db.iterator(ro)
      val startKey = Key(pid, if (fromSequenceNr < 1L) 1L else fromSequenceNr, 0)
      key = startKey
      iter.seek(startKey.toBytes)
    }

    override def onPush(elem: Nothing, ctx: Context[PersistentRepr]): SyncDirective =
      ctx.absorbTermination()

    // see LeveldbRecovery#replayMessages for reference impl
    override def onPull(ctx: Context[PersistentRepr]): SyncDirective = {
      if (iter.hasNext) {
        val nextEntry = iter.next()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (nextKey.sequenceNr > toSequenceNr) {
          // end iteration here
          ctx.finish() // TODO should it finish or start polling? (answer is most likely start polling)
        } else if (isDeletionKey(nextKey)) {
          // this case is needed to discard old events with deletion marker
          key = nextKey
          onPull(ctx)
        } else if (key.persistenceId == nextKey.persistenceId) {
          val msg = persistentFromBytes(nextEntry.getValue)
          val del = deletion(iter, nextKey)
          if (!del) ctx.push(msg)
        } else ctx.fail(new RuntimeException("TODO why would this happen? Can it even?")) // TODO replace with proper exception or flow
      } else ctx.finish() // TODO should it finish or start polling? (answer is most likely start polling)
    }

    override def postStop(): Unit =
    try iter.close() finally ro.snapshot().close()

    // need to have this to be able to read journal created with 2.3.x, which
    // supported deletion of individual events
    private def deletion(iter: DBIterator, key: Key): Boolean = {
      if (iter.hasNext) {
        val nextEntry = iter.peekNext()
        val nextKey = keyFromBytes(nextEntry.getKey)
        if (key.persistenceId == nextKey.persistenceId && key.sequenceNr == nextKey.sequenceNr && isDeletionKey(nextKey)) {
          iter.next()
          true
        } else false
      } else false
    }
  }

  // TODO only used internally when write-side is active and using leveldb
  // TODO not tested if all this works yet (!)
  /** INTERNAL API */
  private[akka] def useSharedLeveldbStore(sharedLeveldbStore: ActorRef): Unit = {
    import akka.pattern.ask
    implicit val timeout = Timeout(3.seconds) // TODO configurable timeout
    val exposed = (sharedLeveldbStore ? SharedLeveldbStore.ExposeUnderlyingLevelDB).mapTo[SharedLeveldbStore.ExposedUnderlyingLevelDB]
    leveldb = Await.result(exposed, timeout.duration).db // TODO on timeout make it a better err message
  }

  private def unsupportedQueryType[M, T](unknown: Query[T, M]): Nothing =
    throw new scala.IllegalArgumentException(s"${getClass.getSimpleName} does not implement the ${unknown.getClass.getName} query type!")
}

// TODO separate things in write-side such that we can reuse a trait like LeveldbReadOps? (attempted, but they're pretty tightly coupled)
trait LeveldbReadOps {
  def leveldb: DB
  def config: Config
  def serialization: Serialization

  // LeveldbStore.scala

  def leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))

  def leveldbSnapshot(): ReadOptions = leveldbReadOptions.snapshot(leveldb.getSnapshot)

  def persistentFromBytes(a: Array[Byte]): PersistentRepr = serialization.deserialize(a, classOf[PersistentRepr]).get

  def withIterator[R](body: DBIterator ⇒ R): R = {
    val ro = leveldbSnapshot()
    val iterator = leveldb.iterator(ro)
    try {
      body(iterator)
    } finally {
      iterator.close()
      ro.snapshot().close()
    }
  }

  // LeveldbMapping.scala

  private val idOffset = 10
  private var idMap: Map[String, Int] = readIdMap()

  /**
   * Get the mapped numeric id for the specified persistent actor `id`. Creates and
   * stores a new mapping if necessary.
   */
  def numericId(id: String): Int = idMap.get(id) match {
    case None    ⇒ writeIdMapping(id, idMap.size + idOffset)
    case Some(v) ⇒ v
  }

  private def readIdMap(): Map[String, Int] = withIterator { iter ⇒
    iter.seek(keyToBytes(mappingKey(idOffset)))
    readIdMap(Map.empty, iter)
  }

  private def readIdMap(pathMap: Map[String, Int], iter: DBIterator): Map[String, Int] = {
    if (!iter.hasNext) pathMap else {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (!isMappingKey(nextKey)) pathMap else {
        val nextVal = new String(nextEntry.getValue, UTF_8)
        readIdMap(pathMap + (nextVal -> nextKey.mappingId), iter)
      }
    }
  }

  private def writeIdMapping(id: String, numericId: Int): Int = {
    idMap = idMap + (id -> numericId)
    leveldb.put(keyToBytes(mappingKey(numericId)), id.getBytes(UTF_8))
    numericId
  }

}