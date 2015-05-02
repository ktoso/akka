/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.query.tagging

import akka.persistence.PersistentRepr

import scala.collection.immutable

trait TagMapper { // TODO "tag" or "topic" // or TagExtractor or Tagger?

  // TODO can't use the word event I think? It may be "command" IYKWIM
  def tagsFor(event: PersistentRepr): immutable.Set[String] // TODO rethink if PersistentRepr here

}
