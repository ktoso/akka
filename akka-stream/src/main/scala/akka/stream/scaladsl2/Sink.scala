/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.Actor
import akka.actor.Actor.Receive
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.stream.impl2.ActorBasedFlowMaterializer
import org.reactivestreams.Subscriber

import scala.language.implicitConversions

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
trait Sink[-In] {
  /**
   * Connect this `Sink` to a `Tap` and run it. The returned value is the materialized value
   * of the `Tap`, e.g. the `Subscriber` of a [[SubscriberTap]].
   */
  def runWith(tap: TapWithKey[In])(implicit materializer: FlowMaterializer): tap.MaterializedType =
    tap.connect(this).run().materializedTap(tap)

  /**
   * Connect this `Sink` to a `Tap` and run it.
   */
  def runWith(tap: SimpleTap[In])(implicit materializer: FlowMaterializer): Unit =
    tap.connect(this).run()
}

object X {
  val x: Sink[String] = ???
  x.runWith(new PublisherTap(null)) // ok
  x.runWith(PublisherTap(null)) // ok
  x.runWith(PublisherTap[String](null)) // ok

  x.runWith(new SubscriberTap()) // ok
  x.runWith(SubscriberTap()) // ok
  x.runWith(SubscriberTap[String]()) // ok

  val s = ActorSystem()
  import concurrent.duration._
  val schedule: Cancellable = s.scheduler.schedule(1.day, 1.day, null, null)
  schedule.cancel()


  val y: Source[String] = ???
  y.runWith() // nope
  y.runWith(new PublisherDrain()) // nope
  y.runWith(PublisherDrain()) // nope
  y.runWith(PublisherDrain[String]()) // ok

  y.runWith(new SubscriberDrain(null)) // nope
  y.runWith(SubscriberDrain(null)) // nope
  y.runWith(SubscriberDrain[String](null)) // ok

}

object Sink {
  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def apply[T](subscriber: Subscriber[T]): Drain[T] = SubscriberDrain(subscriber)

  /**
   * Creates a `Sink` by using an empty [[FlowGraphBuilder]] on a block that expects a [[FlowGraphBuilder]] and
   * returns the `UndefinedSource`.
   */
  def apply[T]()(block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] =
    createSinkFromBuilder(new FlowGraphBuilder(), block)

  /**
   * Creates a `Sink` by using a FlowGraphBuilder from this [[PartialFlowGraph]] on a block that expects
   * a [[FlowGraphBuilder]] and returns the `UndefinedSource`.
   */
  def apply[T](graph: PartialFlowGraph)(block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] =
    createSinkFromBuilder(new FlowGraphBuilder(graph.graph), block)

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Drain[T] = CancelDrain

  private def createSinkFromBuilder[T](builder: FlowGraphBuilder, block: FlowGraphBuilder ⇒ UndefinedSource[T]): Sink[T] = {
    val in = block(builder)
    builder.partialBuild().toSink(in)
  }
}
