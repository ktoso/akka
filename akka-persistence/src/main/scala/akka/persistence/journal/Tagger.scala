/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.journal

import scala.collection.immutable

// TODO "tag" or "topic" // or TagExtractor or TagMapper or Tagger?
trait Tagger[T] {
  //#tag-mapper-api
  // TODO can't use the word event I think? It may be "command" IYKWIM
  def tagsFor(message: T): immutable.Set[String] // TODO rethink if PersistentRepr here
  //#tag-mapper-api
}

private[akka] object NoopTagger extends Tagger[Any] {
  override def tagsFor(message: Any): Set[String] = Set.empty
}

private[akka] final case class CombinedTagger(taggers: immutable.Seq[Tagger[Any]]) extends Tagger[Any] {
  override def tagsFor(message: Any): Set[String] =
    taggers.foldLeft(Set.empty[String])((tags, tagger) ⇒ tags ++ tagger.tagsFor(message))
}