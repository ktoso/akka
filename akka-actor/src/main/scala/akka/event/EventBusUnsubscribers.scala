/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor._
import akka.event.Logging.simpleName
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class EventStreamUnsubscriber(eventStream: EventStream, debug: Boolean) extends Actor {

  import EventBusUnsubscriber._

  override def preStart() {
    super.preStart()
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

}

/**
 * Watches all actors which subscribe on the given event stream, and unsubscribes them from it when they are Terminated.
 */
class ActorClassificationUnsubscriber(bus: ActorClassification, debug: Boolean) extends Actor {

  import EventBusUnsubscriber._

  override def preStart() {
    super.preStart()
    if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"will monitor $bus"))
  }

  def receive = {
    case Register(actor) ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"registered watch for $actor in $bus"))
      context watch actor

    case Unregister(actor) ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"unregistered watch of $actor in $bus"))
      context unwatch actor

    case Terminated(actor) ⇒
      if (debug) context.system.eventStream.publish(Logging.Debug(simpleName(getClass), getClass, s"actor $actor has terminated, unsubscribing it from $bus"))
      bus unsubscribe actor
  }
}

/**
 * Extension providing factory for [[akka.event.ActorClassificationUnsubscriber]] actors with unique names.
 */
object ActorClassificationUnsubscriber extends ExtensionId[ActorClassificationUnsubscriberFactory] with ExtensionIdProvider {

  private val unsubscribersCount = new AtomicInteger(0)

  def lookup() = ActorClassificationUnsubscriber

  def createExtension(system: ExtendedActorSystem) = {
    new ActorClassificationUnsubscriberFactory(system.asInstanceOf[ActorSystem], unsubscribersCount)
  }
}

class ActorClassificationUnsubscriberFactory(system: ActorSystem, ubsubscribersCount: AtomicInteger) extends Extension {

  private val debug = system.settings.config.getBoolean("akka.actor.debug.event-stream")

  def newUnsubscriber(bus: ActorClassification) =
    system.asInstanceOf[ActorSystemImpl]
      .systemActorOf(props(bus, debug), "actorClassificationUnsubscriber-" + ubsubscribersCount.incrementAndGet())

  private def props(eventBus: ActorClassification, debug: Boolean) = Props(classOf[ActorClassificationUnsubscriber], eventBus, debug)

}

object EventBusUnsubscriber {
  final case class Register(actor: ActorRef)
  final case class Unregister(actor: ActorRef)
}

