package akka.persistence.query

abstract class QueryAttribute

final case class FromSequenceNr(seqNr: Long) {
  require(seqNr >= 0, "seqNr must be >= 0")
}

final case class ToSequenceNr(seqNr: Long) {
  require(seqNr >= 0, "seqNr must be >= 0")
}

final class QueryAttributes(attrs: List[QueryAttribute]) {
  def and(that: QueryAttributes): QueryAttributes = new QueryAttributes(that.attrs ::: attrs)
  def &&(that: QueryAttributes): QueryAttributes = and(that)
}
