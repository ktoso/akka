package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.event.{ Logging, LoggingAdapter }
import akka.persistence._
import akka.util.ConstantFun
import akka.{ actor ⇒ a }

trait EventsourcedStashManagement {
  import akka.actor.typed.scaladsl.adapter._

  protected def log: LoggingAdapter

  protected def extension: Persistence
  protected def context: ActorContext[Any]

  protected val internalStash: StashBuffer[Any]

  /**
   * The returned [[StashOverflowStrategy]] object determines how to handle the message failed to stash
   * when the internal Stash capacity exceeded.
   */
  protected val internalStashOverflowStrategy: StashOverflowStrategy =
    extension.defaultInternalStashOverflowStrategy match {
      case ReplyToStrategy(_) ⇒
        throw new RuntimeException("ReplyToStrategy is not supported in Akka Typed, since there is no sender()!")
      case other ⇒
        other // the other strategies are supported
    }

  protected def stash(msg: Any, behavior: Behavior[Any]): Behavior[Any] = {
    log.debug("Stashing message: " + msg)

    try internalStash.stash(msg) catch {
      case e: StashOverflowException ⇒
        internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val snd: a.ActorRef = null // FIXME can we improve it somehow?
            context.system.deadLetters.tell(DeadLetter(msg, snd, context.self.toUntyped), snd)

          case ReplyToStrategy(response) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }

    behavior
  }

  protected def tryUnstash(ctx: ActorContext[Any], behavior: Behavior[Any]): Behavior[Any] = {
    if (internalStash.nonEmpty) {
      log.debug("Unstashing message: {}", internalStash.head.getClass)
      internalStash.unstash(context, behavior, 1, ConstantFun.scalaIdentityFunction)
    } else behavior
  }

}
