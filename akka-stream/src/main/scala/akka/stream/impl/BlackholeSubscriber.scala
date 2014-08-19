/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.{ Subscriber, Subscription }

/**
 * INTERNAL API
 */

private[akka] class BlackholeSubscriber[T](highWatermark: Int) extends Subscriber[T] {

  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0

  private var subscription: Subscription = _

  override def onSubscribe(sub: Subscription): Unit = {
    subscription = sub
    requestMore()
  }

  override def onError(cause: Throwable): Unit = ()

  override def onComplete(): Unit = ()

  override def onNext(element: T): Unit = {
    println("BLACKHOLE << " + element)
    requested -= 1
    requestMore()
  }

  private def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      subscription.request(amount)
      requested += amount
    }

}
