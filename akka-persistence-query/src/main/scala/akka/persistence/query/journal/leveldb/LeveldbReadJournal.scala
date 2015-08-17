/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.journal.leveldb

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.persistence.query.{EventsByPersistenceId, Hint, Query, scaladsl}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.iq80.leveldb.DB

import scala.concurrent.Await
import scala.concurrent.duration._

object LeveldbReadJournal {
  final val Identifier = "akka.persistence.query.journal.leveldb"
}

class LeveldbReadJournal(system: ExtendedActorSystem) extends scaladsl.ReadJournal {

  var leveldb: DB = _

  // TODO how can we work around this (for plugin writers)?
  private implicit class CastHelpers(val s: Source[Any, Unit]) extends AnyVal {
    def cast[T, M] = s.asInstanceOf[Source[T, M]]
  }

  override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] = q match {
    case EventsByPersistenceId(pid, from, to) => eventsByPersistenceId(pid, from, to).cast
    case unknown                              => unsupportedQueryType(unknown)
  }

  def eventsByPersistenceId(pid: String, from: Long, to: Long): Source[Any, Unit] = {

  }

  // TODO ??? for testing only ???
  def useSharedLeveldbStore(sharedLeveldbStore: ActorRef): Unit = {
    import akka.pattern.ask
    implicit val timeout = Timeout(3.seconds)
    val exposed = (sharedLeveldbStore ? SharedLeveldbStore.ExposeUnderlyingLevelDB).mapTo[SharedLeveldbStore.ExposedUnderlyingLevelDB]
    leveldb = Await.result(exposed, timeout.duration).db
  }

  private def unsupportedQueryType[M, T](unknown: Query[T, M]): Nothing =
    throw new scala.IllegalArgumentException(s"${getClass.getSimpleName} does not implement the ${unknown.getClass.getName} query type!")
}
