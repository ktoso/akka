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
    println("onSubscribe() = " + sub)
    subscription = sub
    requestMore()
  }

  override def onError(cause: Throwable): Unit = {
    println("onError() = ")
  }

  override def onComplete(): Unit = {
    println("onComplete() = ")
  }

  override def onNext(element: T): Unit = {
    println("onNext() = " + element)
    requested -= 1
    requestMore()
  }

  private def requestMore(): Unit =
    if (requested < lowWatermark) {
      println("requestMore() = ")
      val amount = highWatermark - requested
      subscription.request(amount)
      requested += amount
    } else {
      println("nope = ")
    }

}
