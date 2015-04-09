/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.actor.LocalActorRef
import akka.event.{ Logging, LoggingAdapter }
import akka.stream.scaladsl.OperationAttributes
import akka.stream.scaladsl.OperationAttributes.LogLevels

import scala.collection.immutable
import akka.stream.impl.{ ActorFlowMaterializerImpl, FixedSizeBuffer, ReactiveStreamsCompliance }
import akka.stream.stage._
import akka.stream._
import akka.stream.Supervision
import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }
import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] final case class Map[In, Out](f: In ⇒ Out, decider: Supervision.Decider) extends PushStage[In, Out] {
  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = ctx.push(f(elem))

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] final case class Filter[T](p: T ⇒ Boolean, decider: Supervision.Decider) extends PushStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (p(elem)) ctx.push(elem)
    else ctx.pull()

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

private[akka] final object Collect {
  // Cached function that can be used with PartialFunction.applyOrElse to ensure that A) the guard is only applied once,
  // and the caller can check the returned value with Collect.notApplied to query whether the PF was applied or not.
  // Prior art: https://github.com/scala/scala/blob/v2.11.4/src/library/scala/collection/immutable/List.scala#L458
  final val NotApplied: Any ⇒ Any = _ ⇒ Collect.NotApplied
}

private[akka] final case class Collect[In, Out](decider: Supervision.Decider)(pf: PartialFunction[In, Out]) extends PushStage[In, Out] {
  import Collect.NotApplied
  override def onPush(elem: In, ctx: Context[Out]): SyncDirective =
    pf.applyOrElse(elem, NotApplied) match {
      case NotApplied             ⇒ ctx.pull()
      case result: Out @unchecked ⇒ ctx.push(result)
    }

  override def decide(t: Throwable): Supervision.Directive = decider(t)
}

/**
 * INTERNAL API
 */
private[akka] final case class MapConcat[In, Out](f: In ⇒ immutable.Seq[Out], decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private var currentIterator: Iterator[Out] = Iterator.empty

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    currentIterator = f(elem).iterator
    if (currentIterator.isEmpty) ctx.pull()
    else ctx.push(currentIterator.next())
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (currentIterator.hasNext) ctx.push(currentIterator.next())
    else if (ctx.isFinishing) ctx.finish()
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective =
    ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): MapConcat[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Take[T](count: Long) extends PushStage[T, T] {
  private var left: Long = count

  override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
    left -= 1
    if (left > 0) ctx.push(elem)
    else if (left == 0) ctx.pushAndFinish(elem)
    else ctx.finish() //Handle negative take counts
  }
}

/**
 * INTERNAL API
 */
private[akka] final case class Drop[T](count: Long) extends PushStage[T, T] {
  private var left: Long = count
  override def onPush(elem: T, ctx: Context[T]): SyncDirective =
    if (left > 0) {
      left -= 1
      ctx.pull()
    } else ctx.push(elem)
}

/**
 * INTERNAL API
 */
private[akka] final case class Scan[In, Out](zero: Out, f: (Out, In) ⇒ Out, decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private var aggregator = zero

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    val old = aggregator
    aggregator = f(old, elem)
    ctx.push(old)
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (ctx.isFinishing) ctx.pushAndFinish(aggregator)
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective = ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Scan[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out, decider: Supervision.Decider) extends PushPullStage[In, Out] {
  private var aggregator = zero

  override def onPush(elem: In, ctx: Context[Out]): SyncDirective = {
    aggregator = f(aggregator, elem)
    ctx.pull()
  }

  override def onPull(ctx: Context[Out]): SyncDirective =
    if (ctx.isFinishing) ctx.pushAndFinish(aggregator)
    else ctx.pull()

  override def onUpstreamFinish(ctx: Context[Out]): TerminationDirective = ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Fold[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Grouped[T](n: Int) extends PushPullStage[T, immutable.Seq[T]] {
  private val buf = {
    val b = Vector.newBuilder[T]
    b.sizeHint(n)
    b
  }
  private var left = n

  override def onPush(elem: T, ctx: Context[immutable.Seq[T]]): SyncDirective = {
    buf += elem
    left -= 1
    if (left == 0) {
      val emit = buf.result()
      buf.clear()
      left = n
      ctx.push(emit)
    } else ctx.pull()
  }

  override def onPull(ctx: Context[immutable.Seq[T]]): SyncDirective =
    if (ctx.isFinishing) {
      val elem = buf.result()
      buf.clear() //FIXME null out the reference to the `buf`?
      left = n
      ctx.pushAndFinish(elem)
    } else ctx.pull()

  override def onUpstreamFinish(ctx: Context[immutable.Seq[T]]): TerminationDirective =
    if (left == n) ctx.finish()
    else ctx.absorbTermination()
}

/**
 * INTERNAL API
 */
private[akka] final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy) extends DetachedStage[T, T] {
  import OverflowStrategy._

  private val buffer = FixedSizeBuffer[T](size)

  override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective =
    if (ctx.isHoldingDownstream) ctx.pushAndPull(elem)
    else enqueueAction(ctx, elem)

  override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
    if (ctx.isFinishing) {
      val elem = buffer.dequeue().asInstanceOf[T]
      if (buffer.isEmpty) ctx.pushAndFinish(elem)
      else ctx.push(elem)
    } else if (ctx.isHoldingUpstream) ctx.pushAndPull(buffer.dequeue().asInstanceOf[T])
    else if (buffer.isEmpty) ctx.holdDownstream()
    else ctx.push(buffer.dequeue().asInstanceOf[T])
  }

  override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective =
    if (buffer.isEmpty) ctx.finish()
    else ctx.absorbTermination()

  val enqueueAction: (DetachedContext[T], T) ⇒ UpstreamDirective = {
    overflowStrategy match {
      case DropHead ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropHead()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case DropTail ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.dropTail()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case DropBuffer ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) buffer.clear()
        buffer.enqueue(elem)
        ctx.pull()
      }
      case Backpressure ⇒ { (ctx, elem) ⇒
        buffer.enqueue(elem)
        if (buffer.isFull) ctx.holdUpstream()
        else ctx.pull()
      }
      case Fail ⇒ { (ctx, elem) ⇒
        if (buffer.isFull) ctx.fail(new Fail.BufferOverflowException(s"Buffer overflow (max capacity was: $size)!"))
        else {
          buffer.enqueue(elem)
          ctx.pull()
        }
      }
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] final case class Completed[T]() extends PushPullStage[T, T] {
  override def onPush(elem: T, ctx: Context[T]): SyncDirective = ctx.finish()
  override def onPull(ctx: Context[T]): SyncDirective = ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class Conflate[In, Out](seed: In ⇒ Out, aggregate: (Out, In) ⇒ Out,
                                                 decider: Supervision.Decider) extends DetachedStage[In, Out] {
  private var agg: Any = null

  override def onPush(elem: In, ctx: DetachedContext[Out]): UpstreamDirective = {
    agg =
      if (agg == null) seed(elem)
      else aggregate(agg.asInstanceOf[Out], elem)

    if (!ctx.isHoldingDownstream) ctx.pull()
    else {
      val result = agg.asInstanceOf[Out]
      agg = null
      ctx.pushAndPull(result)
    }
  }

  override def onPull(ctx: DetachedContext[Out]): DownstreamDirective = {
    if (ctx.isFinishing) {
      if (agg == null) ctx.finish()
      else {
        val result = agg.asInstanceOf[Out]
        agg = null
        ctx.pushAndFinish(result)
      }
    } else if (agg == null) ctx.holdDownstream()
    else {
      val result = agg.asInstanceOf[Out]
      if (result == null) throw new NullPointerException
      agg = null
      ctx.push(result)
    }
  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = ctx.absorbTermination()

  override def decide(t: Throwable): Supervision.Directive = decider(t)

  override def restart(): Conflate[In, Out] = copy()
}

/**
 * INTERNAL API
 */
private[akka] final case class Expand[In, Out, Seed](seed: In ⇒ Seed, extrapolate: Seed ⇒ (Out, Seed)) extends DetachedStage[In, Out] {
  private var s: Seed = _
  private var started: Boolean = false
  private var expanded: Boolean = false

  override def onPush(elem: In, ctx: DetachedContext[Out]): UpstreamDirective = {
    s = seed(elem)
    started = true
    expanded = false
    if (ctx.isHoldingDownstream) {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      ctx.pushAndPull(emit)
    } else ctx.holdUpstream()
  }

  override def onPull(ctx: DetachedContext[Out]): DownstreamDirective = {
    if (ctx.isFinishing) {
      if (!started) ctx.finish()
      else ctx.pushAndFinish(extrapolate(s)._1)
    } else if (!started) ctx.holdDownstream()
    else {
      val (emit, newS) = extrapolate(s)
      s = newS
      expanded = true
      if (ctx.isHoldingUpstream) ctx.pushAndPull(emit)
      else ctx.push(emit)
    }

  }

  override def onUpstreamFinish(ctx: DetachedContext[Out]): TerminationDirective = {
    if (expanded) ctx.finish()
    else ctx.absorbTermination()
  }

  final override def decide(t: Throwable): Supervision.Directive = Supervision.Stop

  final override def restart(): Expand[In, Out, Seed] =
    throw new UnsupportedOperationException("Expand doesn't support restart")
}

/**
 * INTERNAL API
 */
private[akka] object MapAsync {
  val NotYetThere = Failure(new Exception)
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsync[In, Out](parallelism: Int, f: In ⇒ Future[Out], decider: Supervision.Decider)
  extends AsyncStage[In, Out, (Int, Try[Out])] {
  import MapAsync._

  type Notification = (Int, Try[Out])

  private var callback: AsyncCallback[Notification] = _
  private val elemsInFlight = FixedSizeBuffer[Try[Out]](parallelism)

  override def initAsyncInput(ctx: AsyncContext[Out, Notification]): Unit = {
    callback = ctx.getAsyncCallback()
  }

  override def decide(ex: Throwable) = decider(ex)

  override def onPush(elem: In, ctx: AsyncContext[Out, Notification]) = {
    val future = f(elem)
    val idx = elemsInFlight.enqueue(NotYetThere)
    future.onComplete(t ⇒ callback.invoke((idx, t)))(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
    if (elemsInFlight.isFull) ctx.holdUpstream()
    else ctx.pull()
  }

  override def onPull(ctx: AsyncContext[Out, (Int, Try[Out])]) = {
    @tailrec def rec(hasFreedUpSpace: Boolean): DownstreamDirective =
      if (elemsInFlight.isEmpty && ctx.isFinishing) ctx.finish()
      else if (elemsInFlight.isEmpty || elemsInFlight.peek == NotYetThere) {
        if (hasFreedUpSpace && ctx.isHoldingUpstream) ctx.holdDownstreamAndPull()
        else ctx.holdDownstream()
      } else elemsInFlight.dequeue() match {
        case Failure(ex) ⇒ rec(true)
        case Success(elem) ⇒
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
      }
    rec(false)
  }

  override def onAsyncInput(input: (Int, Try[Out]), ctx: AsyncContext[Out, Notification]) = {
    @tailrec def rec(): Directive =
      if (elemsInFlight.isEmpty && ctx.isFinishing) ctx.finish()
      else if (elemsInFlight.isEmpty || elemsInFlight.peek == NotYetThere) ctx.ignore()
      else elemsInFlight.dequeue() match {
        case Failure(ex) ⇒ rec()
        case Success(elem) ⇒
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
      }

    input match {
      case (idx, f @ Failure(ex)) ⇒
        if (decider(ex) != Supervision.Stop) {
          elemsInFlight.put(idx, f)
          if (ctx.isHoldingDownstream) rec()
          else ctx.ignore()
        } else ctx.fail(ex)
      case (idx, s: Success[_]) ⇒
        val ex = try {
          ReactiveStreamsCompliance.requireNonNullElement(s.value)
          elemsInFlight.put(idx, s)
          null: Exception
        } catch {
          case NonFatal(ex) ⇒
            if (decider(ex) != Supervision.Stop) {
              elemsInFlight.put(idx, Failure(ex))
              null: Exception
            } else ex
        }
        if (ex != null) ctx.fail(ex)
        else if (ctx.isHoldingDownstream) rec()
        else ctx.ignore()
    }
  }

  override def onUpstreamFinish(ctx: AsyncContext[Out, Notification]) =
    if (ctx.isHoldingUpstream || !elemsInFlight.isEmpty) ctx.absorbTermination()
    else ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class MapAsyncUnordered[In, Out](parallelism: Int, f: In ⇒ Future[Out], decider: Supervision.Decider)
  extends AsyncStage[In, Out, Try[Out]] {

  private var callback: AsyncCallback[Try[Out]] = _
  private var inFlight = 0
  private val buffer = FixedSizeBuffer[Out](parallelism)

  private def todo = inFlight + buffer.used

  override def initAsyncInput(ctx: AsyncContext[Out, Try[Out]]): Unit = {
    callback = ctx.getAsyncCallback()
  }

  override def decide(ex: Throwable) = decider(ex)

  override def onPush(elem: In, ctx: AsyncContext[Out, Try[Out]]) = {
    val future = f(elem)
    inFlight += 1
    future.onComplete(callback.invoke)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext)
    if (todo == parallelism) ctx.holdUpstream()
    else ctx.pull()
  }

  override def onPull(ctx: AsyncContext[Out, Try[Out]]) =
    if (buffer.isEmpty) {
      if (ctx.isFinishing && inFlight == 0) ctx.finish() else ctx.holdDownstream()
    } else {
      val elem = buffer.dequeue()
      if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
      else ctx.push(elem)
    }

  override def onAsyncInput(input: Try[Out], ctx: AsyncContext[Out, Try[Out]]) = {
    def ignoreOrFail(ex: Throwable) =
      if (decider(ex) == Supervision.Stop) ctx.fail(ex)
      else if (ctx.isHoldingUpstream) ctx.pull()
      else ctx.ignore()

    inFlight -= 1
    input match {
      case Failure(ex) ⇒ ignoreOrFail(ex)
      case Success(elem) ⇒
        if (elem == null) {
          val ex = ReactiveStreamsCompliance.elementMustNotBeNullException
          ignoreOrFail(ex)
        } else if (ctx.isHoldingDownstream) {
          if (ctx.isHoldingUpstream) ctx.pushAndPull(elem)
          else ctx.push(elem)
        } else {
          buffer.enqueue(elem)
          ctx.ignore()
        }
    }
  }

  override def onUpstreamFinish(ctx: AsyncContext[Out, Try[Out]]) =
    if (todo > 0) ctx.absorbTermination()
    else ctx.finish()
}

/**
 * INTERNAL API
 */
private[akka] final case class Log[T](name: String, extract: T ⇒ Any, logAdapter: Option[LoggingAdapter]) extends PushStage[T, T] {
  import Log._
  private var log: LoggingAdapter = _
  private var logLevels: OperationAttributes.LogLevels = _

  override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
    initLogger(ctx) // TODO if we have preStart this could be called in it, instead of in all callbacks

    log.log(logLevels.onElement, "[{}] Element: {}", name, extract(elem))
    ctx.push(elem)
  }

  override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
    initLogger(ctx) // TODO if we have preStart this could be called in it, instead of in all callbacks

    // TODO should be able to determine who my upstream is and give it's name here?
    logLevels.onFailure match {
      case Logging.ErrorLevel ⇒ log.error(cause, "[{}] Upstream failed.", name)
      case level              ⇒ log.log(level, "[{}] Upstream failed, cause: {}: {}", name, Logging.simpleName(cause.getClass), cause.getMessage)
    }
    super.onUpstreamFailure(cause, ctx)
  }

  override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
    initLogger(ctx) // TODO if we have preStart this could be called in it, instead of in all callbacks

    // TODO should be able to determine who my upstream is and give it's name here?
    log.log(logLevels.onFinish, "[{}] Upstream finished.", name)
    super.onUpstreamFinish(ctx)
  }

  /** Idempotent initialization of logger and log levels */
  def initLogger(ctx: Context[T]): Unit =
    if (log == null) {
      logLevels = ctx.attributes.logLevels.getOrElse(DefaultLogLevels)

      log = logAdapter getOrElse {
        val sys = ctx.materializer.asInstanceOf[ActorFlowMaterializerImpl].system
        Logging(sys, "akka.stream.Log")
      }
    }
}

/**
 * INTERNAL API
 */
private[akka] object Log {
  private final val DefaultLogLevels = LogLevels(onElement = Logging.DebugLevel, onFinish = Logging.DebugLevel, onFailure = Logging.ErrorLevel)
}