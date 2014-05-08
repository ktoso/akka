/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.{ Subscription, Subscriber }
import java.util.concurrent.atomic.AtomicReference

class BlackholeConsumer[T](callback: T ⇒ Unit) extends Consumer[T] {
  override def getSubscriber: Subscriber[T] =
    new BlackholeSubscriber(callback).asInstanceOf[Subscriber[T]]
}

class BlackholeSubscriber[T](callback: T ⇒ Unit) extends Subscriber[T] {

  private val subscription = new AtomicReference[Option[Subscription]]()

  override def onSubscribe(sub: Subscription): Unit = {
    subscription.get() match {
      case Some(s) ⇒ sub.cancel()
      case None    ⇒ subscription.compareAndSet(None, Some(sub))
    }
  }

  override def onError(cause: Throwable): Unit = subscription.get.foreach(_.cancel())

  override def onComplete(): Unit = subscription.get.foreach(_.cancel())

  override def onNext(element: T): Unit = callback(element)
}
