/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor._
import akka.event.Logging.simpleName

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class EventStreamUnsubscriber(eventStream: EventStream, debug: Boolean) extends Actor {

  import EventStreamUnsubscriber._

  override def preStart() {
    if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $eventStream"))
    eventStream initUnsubscriber self
  }

  def receive = {
    case Register(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
      context watch actor

    case Unregister(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor"))
      context unwatch actor

    case Terminated(actor) ⇒
      if (debug) eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $eventStream, because it was terminated"))
      eventStream unsubscribe actor
  }
}

object EventStreamUnsubscriber {

  def props(eventStream: EventStream, debug: Boolean = false) =
    Props(classOf[EventStreamUnsubscriber], eventStream, debug)

  final case class Register(actor: ActorRef)
  final case class Unregister(actor: ActorRef)
}

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class ActorClassificationUnsubscriber(classification: ActorClassification) extends Actor {

  import EventStreamUnsubscriber._

  def receive = {
    case Register(actor)   ⇒ context watch actor
    case Unregister(actor) ⇒ context unwatch actor
    case Terminated(actor) ⇒ classification unsubscribe actor
  }
}

object ActorClassificationUnsubscriber {

  def props(eventBus: EventBus) = Props(classOf[ActorClassificationUnsubscriber], eventBus)
}

object Unsubscriber {
  final case class Register(actor: ActorRef)
  final case class Unregister(actor: ActorRef)
}