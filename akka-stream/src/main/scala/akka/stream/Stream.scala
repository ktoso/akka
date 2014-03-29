/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import org.reactivestreams.api.{ Consumer, Producer }
import scala.util.control.NonFatal
import akka.stream.impl._
import akka.actor.ActorRefFactory
import scala.concurrent.ExecutionContext

object Stream {
  def apply[T](producer: Producer[T]): Stream[T] = StreamImpl(producer, Nil)
  def apply[T](iterator: Iterator[T])(implicit ec: ExecutionContext): Stream[T] = StreamImpl(new IteratorProducer(iterator), Nil)
  def apply[T](seq: immutable.Seq[T]) = ???
}

trait Stream[T] {
  def map[U](f: T ⇒ U): Stream[U]
  def filter(p: T ⇒ Boolean): Stream[T]
  def drop(n: Int): Stream[T]
  def grouped(n: Int): Stream[immutable.Seq[T]]
  def mapConcat[U](f: T ⇒ immutable.Seq[U]): Stream[U]
  def transform[S, U](zero: S)(f: (S, T) ⇒ (S, immutable.Seq[U])): Stream[U]
  // FIXME order of the parameters?
  def transform[S, U](zero: S, onComplete: S ⇒ immutable.Seq[U])(f: (S, T) ⇒ (S, immutable.Seq[U])): Stream[U]
  def toProducer(generator: ProcessorGenerator): Producer[T]
}

// FIXME is Processor the right naming here?
object ProcessorGenerator {
  def apply(settings: GeneratorSettings)(implicit context: ActorRefFactory): ProcessorGenerator =
    new ActorBasedProcessorGenerator(settings, context)
}

trait ProcessorGenerator {
  /**
   * INTERNAL API
   * ops are stored in reverse order
   */
  private[akka] def toProducer[I, O](producerToExtend: Producer[I], ops: List[Ast.AstNode]): Producer[O]
}

// FIXME default values? Should we have an extension that reads from config?
case class GeneratorSettings(
  initialFanOutBufferSize: Int = 4,
  maxFanOutBufferSize: Int = 16,
  initialInputBufferSize: Int = 4,
  maximumInputBufferSize: Int = 16) {

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0
  require(initialFanOutBufferSize > 0, "initialFanOutBufferSize must be > 0")
  require(maxFanOutBufferSize > 0, "maxFanOutBufferSize must be > 0")
  require(initialFanOutBufferSize <= maxFanOutBufferSize,
    s"initialFanOutBufferSize($initialFanOutBufferSize) must be <= maxFanOutBufferSize($maxFanOutBufferSize)")

  require(initialInputBufferSize > 0, "initialInputBufferSize must be > 0")
  require(isPowerOfTwo(initialInputBufferSize), "initialInputBufferSize must be a power of two")
  require(maximumInputBufferSize > 0, "maximumInputBufferSize must be > 0")
  require(isPowerOfTwo(maximumInputBufferSize), "initialInputBufferSize must be a power of two")
  require(initialInputBufferSize <= maximumInputBufferSize,
    s"initialInputBufferSize($initialInputBufferSize) must be <= maximumInputBufferSize($maximumInputBufferSize)")
}

