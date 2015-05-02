/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.tagging

import akka.persistence.PersistentRepr

import scala.collection.immutable.Seq

object TaggingSpec {
  class FirstLetterTagger extends TagMapper {
    override def tagsFor(event: PersistentRepr): Seq[String] = ???
  }
}

class TaggingSpec {

}
