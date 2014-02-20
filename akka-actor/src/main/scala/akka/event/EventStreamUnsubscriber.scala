/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor._
import akka.event.Logging.simpleName

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class EventStreamUnsubscriber(eventStream: EventStream, debug: Boolean = false) extends Actor with ActorLogging {

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
  final case class Register(actor: ActorRef)
  final case class Unregister(actor: ActorRef)
}