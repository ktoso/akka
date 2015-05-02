package akka.persistence.query

abstract class PersistenceQuery[T] // TODO or is it JournalQuery? I'd prefer PersistenceQuery

/** Query journal for events by persistenceId */
final case class ByPersistenceIdQuery[T](persistenceId: String) extends PersistenceQuery[T]
