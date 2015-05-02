/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query // TODO or "queries"? Or better name?

import akka.actor.ActorSystem
import akka.stream._
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Future

class QuerySideAPIDrafts {

  // TODO questions:
  // - is a "query side plugin" also IS-A "simple plugin"?
  // - query side must depend on akka-streams, so separate module
  // - should we ask plugins to expose Source[] or Publisher[]? Since it's akka already it can be Source.
  // - if separate module its methods can't be "on Persistence(sys).", too bad hm...
  // - think about SnapshotStore, it will become stable, do we need anything from it for the query side?
  // - what about read side recovery from a snapshot? How to Type this - query[Snap, Events].headAndTail ?
  // - decide on names "journal" for both plugins or something else? now is the time to rename if we need to

  // QUERY SIDE FEATURE - tagging
  val config = ConfigFactory.parseString(
    """
      |akka.persistence {
      |  taggers {
      |    age-group = "com.example.tagger.AgeGroupTagger"
      |    country = "com.example.tagger.CountryTagger"
      |  }
      |
      |  tagging {
      |    "com.example.domain.Request" = country
      |    "com.example.domain.User"    = [ age-group, country ]
      |  }
      |}
    """.stripMargin)
  // END OF QUERY SIDE FEATURE

  implicit val system = ActorSystem()
  implicit val mat = ActorFlowMaterializer()

  // user model, mock events
  sealed class UserEvent
  final case class NameChanged() extends UserEvent
  final case class AgeChanged(ageNow: Int) extends UserEvent
  final case class EmailChanged() extends UserEvent

  object Persistence {
    def persistenceIds(): Stream[String] = ??? // QUERY SIDE FEATURE

    // TODO decide on name, candidates: stream, query, ...
    // TODO IT MUST INCLUDE METADATA ABOUT THE QUERY RESULTS - is it ordered? is it finite?
    def query[T <: Any](q: PersistenceQuery[T], attrs: QueryAttribute = QueryAttribute.none): Stream[T] = ??? // TODO is it known which journal to ask?

    // TODO if we have highest we can combine it with query(fromId = ...) to get "get only new things"
    def highestSeqNr(persistenceId: String): Future[Long] = ???
  }

  // persistence ids

  Persistence.persistenceIds()
    .filter { _ startsWith "product" }
    .foreach { id ⇒
      system.actorSelection(s"/user/blog/$id") ! "hello!"
    }

  // advanced queries
  trait QueryAttribute {
    def and(that: QueryAttribute): this.type = this
    def &&(that: QueryAttribute): this.type = this
  }
  trait RangeQueryAttribute extends QueryAttribute

  object QueryAttribute {
    val none = new QueryAttribute {}
    def from(i: Long): RangeQueryAttribute = ???
    def to(i: Long): RangeQueryAttribute = ???
    def range(from: Long, to: Long): RangeQueryAttribute = ???
  }
  import QueryAttribute._

  trait PersistenceQuery[T]
  // TODO where do we want attrs, on thr query method or on the query objects
  final case class ByPersistenceId[T](id: String, attrs: QueryAttribute = QueryAttribute.none)
    extends PersistenceQuery[T]

  val su0: Stream[Any] = Persistence.query(ByPersistenceId("user-1337"))
  val su1: Stream[Any] = Persistence.query(ByPersistenceId("user-1337"), from(0) and to(100)) // these are "write side"'s minimum requirements

  // "query to highest seq nr and complete"
  val su1: Future[Stream[Any]] = Persistence.highestSeqNr("user-1337") map { nr ⇒ // TODO Metadata instead of seqNr
    Persistence.query(ByPersistenceId("user-1337"), to(nr))
  }

  // TODO maybe query() should give (Metadata + Stream) then metadata could contain the "highest id",
  // maybe that's wasteful though - to force checking of higest id when wanting to stream through?


  // QUERY SIDE, queries can be typed
  val st: Stream[UserEvent] = Persistence.query[UserEvent](ByPersistenceId("user-1337"), from(0) and to(100))


  // QUERY SIDE, queries by "tag"
  case class ByTag[T](tag: String) // TODO what about "by tags"
    extends PersistenceQuery[T]

  val stag = Persistence.query(ByTag("country-pl"))


  // QUERY SIDE, user provided advanced queries
  case class BySql[T](sqlQuery: String, args: Any*) // user can pick best thing that fits them here ofc
    extends PersistenceQuery[T]

  Persistence.query[UserEvent](BySql("""SELECT * FROM users u WHERE u.age >= {} AND u.age <= {} """, 20, 30))


  // QUERY SIDE, taggers

  trait TagExtractor {
    def extract(event: Any): immutable.Seq[String]
  }

  final class AgeGroupTagger extends TagExtractor {
    val minor = List("minor")
    val adult = List("adult")
    override def extract(event: Any): immutable.Seq[String] = event match {
      case AgeChanged(age) if age < 18 ⇒ minor
      case _: AgeChanged               ⇒ adult
    } // We handle it such that PF does not apply => no tags
  }

}
