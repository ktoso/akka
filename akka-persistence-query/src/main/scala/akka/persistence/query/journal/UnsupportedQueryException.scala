package akka.persistence.query.journal

import akka.persistence.query.PersistenceQuery

class UnsupportedQueryException[T](query: PersistenceQuery[T], message: String) extends RuntimeException(message)