package akka.persistence.query.journal

import akka.actor.Cancellable
import akka.persistence.query._
import akka.stream.scaladsl.Source

/** The journal MUST inform the recipient of the stream about the guarantees it provides about the stream. */
trait QueryMetadata { // TODO make this interface as small as possible, and allow journals to provide more specific ones
  def deterministicOrder: Boolean

  /**
   * If the stream begins by replaying existing events this property is true.
   * If the stream will only include incoming (or "future") elements then this propert must be false.
   */
  def isReplay: Boolean // TODO maybe useless?

  /**
   * If the journal starts the stream by replaying existing events,
   * this property determines if it will complete when it determines that the "replay is done".
   *
   * If this property is false and [[isReplay]] is true, the stream can be described as "read the
   * previously persisted events, and from then on emit all new incoming events into the stream".
   */
  def continueAfterReplay: Boolean

  /** true if the returned stream is possibly infinite, as in "give me persistence ids" with no limit set. */
  def infinite: Boolean // TODO needs better name
}

trait Query[T] { // TODO make sure to use same class for Java and Scala
  def metadata: QueryMetadata
  def results: Source[T, Cancellable]
}

abstract class QueryJournal {
  def queryPersistenceIds: Query[String]
  def queryMessages[T](query: PersistenceQuery[T], attrs: QueryAttributes): Query[T] // TODO "Query" name is a bit off

  def rejectQuery[T](q: PersistenceQuery[T], reason: String): Query[T] =
    new Query[T] {
      override def results: Source[T, Cancellable] =
        Source.failed(new UnsupportedQueryException(q, reason))
          .mapMaterialized(_ => _cancelled)
      override def metadata: QueryMetadata = _cancelledQueryMetadata
    }

  private[this] val _cancelledQueryMetadata = new QueryMetadata {
    override def isReplay: Boolean = false
    override def deterministicOrder: Boolean = false
    override def continueAfterReplay: Boolean = false
    override def infinite: Boolean = false
  }
  private[this] val _cancelled = new Cancellable() {
    override def cancel(): Boolean = true
    override def isCancelled: Boolean = true
  }
}

/** someone would implement it like this */
class MockQueryJournal extends QueryJournal {
  case class DeterministicFiniteQuery[T](override val results: Source[T, Cancellable]) extends Query[T] {
    override val metadata = new QueryMetadata {
      override val isReplay = true
      override val deterministicOrder = false
      override val continueAfterReplay = true
      override val infinite= false
    }
  }
  
  override def queryPersistenceIds: Query[String] = {
    val s: Source[String, Cancellable] = ??? // TODO ask some DB
    DeterministicFiniteQuery(s)
  }

  def queryMessages[T](query: PersistenceQuery[T], attrs: QueryAttributes) = query match {
    case ByPersistenceIdQuery(id) =>
    case _ =>
      rejectQuery(query, s"Unsuported query type, only [${classOf[ByPersistenceIdQuery].getName}] is supported.")
  }

  private def
}