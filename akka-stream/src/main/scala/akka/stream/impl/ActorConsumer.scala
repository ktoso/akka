/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.{ Subscriber, Subscription }

import Ast.{ AstNode, Recover, Transform }
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, actorRef2Scala }
import akka.stream.GeneratorSettings

class ActorSubscriber[T]( final val impl: ActorRef) extends Subscriber[T] {
  override def onError(cause: Throwable): Unit = impl ! OnError(cause)
  override def onComplete(): Unit = impl ! OnComplete
  override def onNext(element: T): Unit = impl ! OnNext(element)
  override def onSubscribe(subscription: Subscription): Unit = impl ! OnSubscribe(subscription)
}

trait ActorConsumerLike[T] extends Consumer[T] {
  def impl: ActorRef
  override val getSubscriber: Subscriber[T] = new ActorSubscriber[T](impl)
}

class ActorConsumer[T]( final val impl: ActorRef) extends ActorConsumerLike[T]

object ActorConsumer {
  import Ast._

  def props(gen: GeneratorSettings, op: AstNode) = op match {
    case t: Transform ⇒ Props(new TransformActorConsumer(gen, t))
    case r: Recover   ⇒ Props(new RecoverActorConsumer(gen, r))
  }
}

abstract class AbstractActorConsumer(val settings: GeneratorSettings) extends Actor {
  import ActorProcessor._

  /**
   * Consume one element synchronously: the Actor mailbox is the queue.
   */
  def onNext(elem: Any): Unit

  /**
   * Must call shutdown() eventually.
   */
  def onError(e: Throwable): Unit

  /**
   * Must call shutdown() eventually.
   */
  def onComplete(): Unit

  /**
   * Terminate processing after the current message; will cancel the subscription if necessary.
   */
  def shutdown(): Unit = context.stop(self)

  context.setReceiveTimeout(settings.upstreamSubscriptionTimeout)

  def receive = {
    case OnSubscribe(sub) ⇒
      context.setReceiveTimeout(Duration.Undefined)
      subscription = Some(sub)
      requestMore
      context.become(active)
    case OnError(cause) ⇒
      onError(cause)
    case OnComplete ⇒
      onComplete()
  }

  private var subscription: Option[Subscription] = None

  private val highWatermark = settings.maximumInputBufferSize
  private val lowWatermark = Math.max(1, highWatermark / 2)
  private var requested = 0
  private def requestMore(): Unit =
    if (requested < lowWatermark) {
      val amount = highWatermark - requested
      subscription.get.requestMore(amount)
      requested += amount
    }
  private def gotOne(): Unit = {
    requested -= 1
    requestMore()
  }

  def active: Receive = {
    case OnSubscribe(sub) ⇒ sub.cancel()
    case OnNext(elem)     ⇒ { gotOne(); onNext(elem) }
    case OnError(cause)   ⇒ { subscription = None; onError(cause) }
    case OnComplete       ⇒ { subscription = None; onComplete() }
  }

  override def postStop(): Unit = {
    subscription foreach (_.cancel())
  }
}

class TransformActorConsumer(_settings: GeneratorSettings, op: Ast.Transform) extends AbstractActorConsumer(_settings) with ActorLogging {
  private var state = op.zero

  private var onCompleteCalled = false
  private def callOnComplete(): Unit = {
    if (!onCompleteCalled) {
      onCompleteCalled = true
      try op.onComplete(state)
      catch { case NonFatal(e) ⇒ log.error(e, "failure during onComplete") }
      shutdown()
    }
  }

  override def onNext(elem: Any): Unit = {
    val (nextState, _) = op.f(state, elem)
    state = nextState
    if (op.isComplete(nextState)) {
      callOnComplete()
    }
  }

  override def onError(cause: Throwable): Unit = {
    log.error(cause, "terminating due to onError")
    shutdown()
  }

  override def onComplete(): Unit = {
    callOnComplete()
  }
}

class RecoverActorConsumer(_settings: GeneratorSettings, op: Ast.Recover) extends TransformActorConsumer(_settings, op.t) {
  override def onNext(elem: Any): Unit = {
    super.onNext(Success(elem))
  }

  override def onError(cause: Throwable): Unit = {
    super.onNext(Failure(cause))
    onComplete()
  }
}
