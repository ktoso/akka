/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import org.reactivestreams.spi.Subscriber
import org.reactivestreams.spi.Subscription
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.Terminated
import akka.stream.GeneratorSettings
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
private[akka] object IterableProducer {
  def props(iterable: immutable.Iterable[Any], settings: GeneratorSettings): Props =
    Props(new IterableProducer(iterable, settings))

  object BasicActorSubscription {
    case object Cancel
    case class RequestMore(elements: Int)
  }

  class BasicActorSubscription(ref: ActorRef)
    extends Subscription {
    import BasicActorSubscription._
    def cancel(): Unit = ref ! Cancel
    def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("The number of requested elements must be > 0")
      else ref ! RequestMore(elements)
    override def toString = "BasicActorSubscription"
  }
}

/**
 * INTERNAL API
 *
 * Elements are produced from the iterator of the iterable. Each subscriber
 * makes use of its own iterable, i.e. each consumer will receive the elements from the
 * beginning of the iterable and it can consume the elements in its own pace.
 */
private[akka] class IterableProducer(iterable: immutable.Iterable[Any], settings: GeneratorSettings) extends Actor {
  import IterableProducer.BasicActorSubscription
  import IterableProducer.BasicActorSubscription.Cancel

  require(iterable.nonEmpty, "Use EmptyProducer for empty iterable")

  var exposedPublisher: ActorPublisher[Any] = _
  var subscribers = Set.empty[Subscriber[Any]]
  var workers = Map.empty[ActorRef, Subscriber[Any]]
  var completed = false

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      context.setReceiveTimeout(settings.downstreamSubscriptionTimeout)
      context.become(waitingForFirstSubscriber)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  def waitingForFirstSubscriber: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber
      context.setReceiveTimeout(Duration.Undefined)
      context.become(active)
  }

  def active: Receive = {
    case SubscribePending ⇒
      exposedPublisher.takePendingSubscribers() foreach registerSubscriber

    case Terminated(worker) ⇒
      val subscriber = workers(worker)
      workers -= worker
      subscribers -= subscriber
      if (subscribers.isEmpty)
        context.stop(self)
  }

  def registerSubscriber(subscriber: Subscriber[Any]): Unit = {
    if (subscribers(subscriber))
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    else {
      val iterator = iterable.iterator
      val worker = context.watch(context.actorOf(IterableProducerWorker.props(iterator, subscriber,
        settings.maximumInputBufferSize)))
      val subscription = new BasicActorSubscription(worker)
      subscribers += subscriber
      workers = workers.updated(worker, subscriber)
      subscriber.onSubscribe(subscription)
    }
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(completed)
  }

}

/**
 * INTERNAL API
 */
private[akka] object IterableProducerWorker {
  def props(iterator: Iterator[Any], subscriber: Subscriber[Any], maxPush: Int): Props =
    Props(new IterableProducerWorker(iterator, subscriber, maxPush))

  private object PushMore
}

/**
 * INTERNAL API
 *
 * Each subscriber is served by this worker actor. It pushes elements to the
 * subscriber immediately when it receives demand, but to allow cancel before
 * pushing everything it sends a PushMore to itself after a batch of elements.
 */
private[akka] class IterableProducerWorker(iterator: Iterator[Any], subscriber: Subscriber[Any], maxPush: Int)
  extends Actor {
  import IterableProducerWorker._
  import IterableProducer.BasicActorSubscription._

  require(iterator.hasNext, "Iterator must not be empty")

  var demand = 0L

  def receive = {
    case RequestMore(elements) ⇒
      demand += elements
      push()
    case PushMore ⇒
      push()
    case Cancel ⇒
      context.stop(self)
  }

  private def push(): Unit = {
    @tailrec def doPush(n: Int): Unit =
      if (demand > 0) {
        demand -= 1
        subscriber.onNext(iterator.next())
        if (!iterator.hasNext) {
          subscriber.onComplete()
          context.stop(self)
        } else if (n == 0 && demand > 0)
          self ! PushMore
        else
          doPush(n - 1)
      }

    try doPush(maxPush) catch {
      case NonFatal(e) ⇒
        subscriber.onError(e)
        context.stop(self)
    }
  }
}

