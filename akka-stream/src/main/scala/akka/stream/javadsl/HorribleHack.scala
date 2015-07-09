package akka.stream.javadsl

import akka.util.Collections.EmptyImmutableSeq

import scala.collection.immutable

// TODO: this is a workaround for SI-8743 and Scala 2.10.5; remove once 2.10 not supported
final class HorribleHack
object HorribleHack {
  def function[T](ret: T): () ⇒ T = () ⇒ ret

  /**
   * Turns an [[java.lang.Iterable]] into an immutable Scala sequence (by copying it).
   */
  def immutableSeq[T](i: java.util.Iterator[T]): immutable.Seq[T] =
    if (i.hasNext) {
      val builder = new immutable.VectorBuilder[T]

      do {
        builder += i.next()
      } while (i.hasNext)

      builder.result()
    } else EmptyImmutableSeq

}