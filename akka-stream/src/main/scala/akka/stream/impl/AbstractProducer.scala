/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.annotation.tailrec
import org.reactivestreams.api
import org.reactivestreams.spi
import SubscriberManagement.ShutDown
import ResizableMultiReaderRingBuffer.NothingToReadException

/**
 * INTERNAL API
 */
private[akka] object SubscriberManagement {

  sealed trait EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit
  }

  object NotReached extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = throw new IllegalStateException("Called apply on NotReached")
  }

  object Completed extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = subscriber.onComplete()
  }

  case class ErrorCompleted(cause: Throwable) extends EndOfStream {
    def apply[T](subscriber: spi.Subscriber[T]): Unit = subscriber.onError(cause)
  }

  val ShutDown = new ErrorCompleted(new IllegalStateException("Cannot subscribe to shut-down spi.Publisher"))
}

/**
 * INTERNAL API
 */
private[akka] trait SubscriptionWithCursor[T] extends spi.Subscription with ResizableMultiReaderRingBuffer.Cursor {
  def subscriber: spi.Subscriber[T]
  def isActive: Boolean = cursor != Int.MinValue
  def deactivate(): Unit = cursor = Int.MinValue

  def dispatch(element: T): Unit = subscriber.onNext(element)

  /////////////// internal interface, no unsynced access from subscriber's thread //////////////

  var requested: Long = 0 // number of requested but not yet dispatched elements
  var cursor: Int = 0 // buffer cursor, set to Int.MinValue if this subscription has been cancelled / terminated
}

/**
 * INTERNAL API
 */
private[akka] trait SubscriberManagement[T] extends ResizableMultiReaderRingBuffer.Cursors {
  import SubscriberManagement._
  type S <: SubscriptionWithCursor[T]
  type Subscriptions = List[S]

  def initialBufferSize: Int
  def maxBufferSize: Int

  /**
   * called when we are ready to consume more elements from our upstream
   * MUST NOT call pushToDownstream
   */
  protected def requestFromUpstream(elements: Int): Unit

  /**
   * called before `shutdown()` if the stream is *not* being regularly completed
   * but shut-down due to the last subscriber having cancelled its subscription
   */
  protected def cancelUpstream(): Unit

  /**
   * called when the spi.Publisher/Processor is ready to be shut down
   */
  protected def shutdown(completed: Boolean): Unit

  /**
   * Use to register a subscriber
   */
  protected def createSubscription(subscriber: spi.Subscriber[T]): S

  private[this] val buffer = new ResizableMultiReaderRingBuffer[T](initialBufferSize, maxBufferSize, this)

  protected def bufferDebug: String = buffer.toString

  // optimize for small numbers of subscribers by keeping subscribers in a plain list
  private[this] var subscriptions: Subscriptions = Nil

  // number of elements already requested but not yet received from upstream
  private[this] var pendingFromUpstream: Long = 0

  // if non-null, holds the end-of-stream state
  private[this] var endOfStream: EndOfStream = NotReached

  def cursors = subscriptions

  /**
   * more demand was signaled from a given subscriber
   */
  protected def moreRequested(subscription: S, elements: Int): Unit =
    if (subscription.isActive) {

      // returns Long.MinValue if the subscription is to be terminated
      @tailrec def dispatchFromBufferAndReturnRemainingRequested(requested: Long, eos: EndOfStream): Long =
        if (requested == 0) {
          // if we are at end-of-stream and have nothing more to read we complete now rather than after the next `requestMore`
          if ((eos ne NotReached) && buffer.count(subscription) == 0) Long.MinValue else 0
        } else if (buffer.count(subscription) > 0) {
          subscription.dispatch(buffer.read(subscription))
          dispatchFromBufferAndReturnRemainingRequested(requested - 1, eos)
        } else if (eos ne NotReached) Long.MinValue
        else requested

      endOfStream match {
        case eos @ (NotReached | Completed) ⇒
          val demand = subscription.requested + elements
          dispatchFromBufferAndReturnRemainingRequested(demand, eos) match {
            case Long.MinValue ⇒
              eos(subscription.subscriber)
              unregisterSubscriptionInternal(subscription)
            case x ⇒
              subscription.requested = x
              requestFromUpstreamIfRequired()
          }
        case ErrorCompleted(_) ⇒ // ignore, the spi.Subscriber might not have seen our error event yet
      }
    }

  private[this] final def requestFromUpstreamIfRequired(): Unit = {
    @tailrec def maxRequested(remaining: Subscriptions, result: Long = 0): Long =
      remaining match {
        case head :: tail ⇒ maxRequested(tail, math.max(head.requested, result))
        case _            ⇒ result
      }
    val desired = Math.min(Int.MaxValue, Math.min(maxRequested(subscriptions), buffer.maxAvailable) - pendingFromUpstream).toInt
    if (desired > 0) {
      pendingFromUpstream += desired
      requestFromUpstream(desired)
    }
  }

  /**
   * this method must be called by the implementing class whenever a new value is available to be pushed downstream
   */
  protected def pushToDownstream(value: T): Unit = {
    @tailrec def dispatch(remaining: Subscriptions, sent: Boolean = false): Boolean =
      remaining match {
        case head :: tail ⇒
          if (head.requested > 0) {
            val element = buffer.read(head)
            head.dispatch(element)
            head.requested -= 1
            dispatch(tail, true)
          } else dispatch(tail, sent)
        case _ ⇒ sent
      }

    endOfStream match {
      case NotReached ⇒
        pendingFromUpstream -= 1
        if (!buffer.write(value)) throw new IllegalStateException("Output buffer overflow")
        if (dispatch(subscriptions)) requestFromUpstreamIfRequired()
      case _ ⇒
        throw new IllegalStateException("pushToDownStream(...) after completeDownstream() or abortDownstream(...)")
    }
  }

  /**
   * this method must be called by the implementing class whenever
   * it has been determined that no more elements will be produced
   */
  protected def completeDownstream(): Unit = {
    if (endOfStream eq NotReached) {
      @tailrec def completeDoneSubscriptions(remaining: Subscriptions, result: Subscriptions = Nil): Subscriptions =
        remaining match {
          case head :: tail ⇒
            if (buffer.count(head) == 0) {
              head.deactivate()
              Completed(head.subscriber)
              completeDoneSubscriptions(tail, result)
            } else completeDoneSubscriptions(tail, head :: result)
          case _ ⇒ result
        }
      endOfStream = Completed
      subscriptions = completeDoneSubscriptions(subscriptions)
      if (subscriptions.isEmpty) shutdown(completed = true)
    } // else ignore, we need to be idempotent
  }

  /**
   * this method must be called by the implementing class to push an error downstream
   */
  protected def abortDownstream(cause: Throwable): Unit = {
    endOfStream = ErrorCompleted(cause)
    subscriptions.foreach(s ⇒ endOfStream(s.subscriber))
    subscriptions = Nil
  }

  /**
   * Register a new subscriber.
   */
  protected def registerSubscriber(subscriber: spi.Subscriber[T]): Unit = endOfStream match {
    case NotReached if subscriptions.exists(_.subscriber eq subscriber) ⇒
      subscriber.onError(new IllegalStateException(s"Cannot subscribe $subscriber twice"))
    case NotReached ⇒
      val newSubscription = createSubscription(subscriber)
      subscriptions ::= newSubscription
      buffer.initCursor(newSubscription)
      subscriber.onSubscribe(newSubscription)
    case Completed if buffer.nonEmpty ⇒
      val newSubscription = createSubscription(subscriber)
      subscriptions ::= newSubscription
      buffer.initCursor(newSubscription)
      subscriber.onSubscribe(newSubscription)
    case eos ⇒
      eos(subscriber)
  }

  /**
   * called from `Subscription::cancel`, i.e. from another thread,
   * override to add synchronization with itself, `subscribe` and `moreRequested`
   */
  protected def unregisterSubscription(subscription: S): Unit =
    unregisterSubscriptionInternal(subscription)

  // must be idempotent
  private def unregisterSubscriptionInternal(subscription: S): Unit = {
    @tailrec def removeFrom(remaining: Subscriptions, result: Subscriptions = Nil): Subscriptions =
      remaining match {
        case head :: tail ⇒ if (head eq subscription) tail reverse_::: result else removeFrom(tail, head :: result)
        case _            ⇒ throw new IllegalStateException("Subscription to unregister not found")
      }
    if (subscription.isActive) {
      subscriptions = removeFrom(subscriptions)
      buffer.onCursorRemoved(subscription)
      subscription.deactivate()
      if (subscriptions.isEmpty) {
        if (endOfStream eq NotReached) {
          endOfStream = ShutDown
          cancelUpstream()
        }
        shutdown(completed = false)
      } else requestFromUpstreamIfRequired() // we might have removed a "blocking" subscriber and can continue now
    } // else ignore, we need to be idempotent
  }
}

/*
 * FIXME: THIS BELOW NEEDS TO BE REMOVED, IT IS NOT USED BY ActorProcessorImpl
 */

/**
 * INTERNAL API
 *
 * Implements basic subscriber management as well as efficient "slowest-subscriber-rate" downstream fan-out support
 * with configurable and adaptive output buffer size.
 */
private[akka] abstract class AbstractProducer[T](val initialBufferSize: Int, val maxBufferSize: Int)
  extends api.Producer[T] with spi.Publisher[T] with SubscriberManagement[T] {
  type S = Subscription

  def getPublisher: spi.Publisher[T] = this

  // called from anywhere, i.e. potentially from another thread,
  // override to add synchronization with itself, `moreRequested` and `unregisterSubscription`
  def subscribe(subscriber: spi.Subscriber[T]): Unit = registerSubscriber(subscriber)

  override def createSubscription(subscriber: spi.Subscriber[T]): S = new Subscription(subscriber)

  protected class Subscription(val subscriber: spi.Subscriber[T]) extends SubscriptionWithCursor[T] {
    override def requestMore(elements: Int): Unit =
      if (elements <= 0) throw new IllegalArgumentException("Argument must be > 0")
      else moreRequested(this, elements) // needs to be able to ignore calls after termination / cancellation

    override def cancel(): Unit = unregisterSubscription(this) // must be idempotent

  }
}

