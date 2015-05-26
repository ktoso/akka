/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ ExtendedActorSystem, ActorSystem, ActorRef, DynamicAccess }
import akka.event.Logging.{ Error, Warning }
import com.typesafe.config.Config
import scala.reflect.ClassTag

/**
 * Instrumentation is attached to actor systems using the `akka.instrumentation`
 * configuration option, specifying a fully qualified class name of an
 * instrumentation implementation. For example:
 *
 * {{{
 * akka.instrumentation = "com.example.SomeInstrumentation"
 * }}}
 *
 * Instrumentation classes must extend [[akka.instrument.Instrumentation]]
 * and have a public constructor which is empty or optionally accepts an
 * [[akka.actor.DynamicAccess]] and/or a [[com.typesafe.config.Config]]
 * parameter. The config object is the same one as used to create the actor
 * system.
 *
 * There are methods to access attached instrumentations on actor systems,
 * to provide user APIs.
 *
 * Accessing instrumentation in Scala:
 * {{{
 * Instrumentation[SomeInstrumentation](system) // throws exception if not found
 *
 * Instrumentation.find[SomeInstrumentation](system) // returns Option
 * }}}
 *
 * Accessing instrumentation in Java:
 * {{{
 * Instrumentation.exists(system, SomeInstrumentation.class); // returns boolean
 *
 * Instrumentation.get(system, SomeInstrumentation.class); // throws exception if not found
 * }}}
 */
object Instrumentation {
  /**
   * Empty placeholder (null) for when there is no context.
   */
  val EmptyContext: AnyRef = null

  /**
   * INTERNAL API. Create the instrumentation for an actor system.
   *
   * Instrumentation classes must extend [[akka.instrument.Instrumentation]]
   * and have a public constructor which is empty or optionally accepts an
   * [[akka.actor.DynamicAccess]] and/or a [[com.typesafe.config.Config]]
   * parameter. The config object is the same one as used to create the actor
   * system.
   *
   *   - If there is no instrumentation then a default empty implementation
   *     with final methods is used ([[akka.instrument.NoInstrumentation]]).
   */
  private[akka] def apply(instrumentation: String, dynamicAccess: DynamicAccess, config: Config): (Instrumentation, Boolean) = {
    instrumentation.length match {
      case 0 ⇒ create("akka.instrument.NoInstrumentation", dynamicAccess, config) -> false // Use reflection to allow JIT optimizations
      case _ ⇒ create(instrumentation, dynamicAccess, config) -> true
    }
  }

  /**
   * Create an instrumentation dynamically from a fully qualified class name.
   * Instrumentation constructors can optionally accept the dynamic access
   * and actor system config, or only actor system config.
   *
   * @param instrumentation fully qualified class name of an instrumentation implementation
   * @param dynamicAccess the dynamic access instance used to load the instrumentation class
   * @param config the config object used to initialize the instrumentation
   */
  def create(instrumentation: String, dynamicAccess: DynamicAccess, config: Config): Instrumentation = {
    val dynamicAndConfigArg = List(classOf[DynamicAccess] -> dynamicAccess, classOf[Config] -> config)
    val configArg = List(classOf[Config] -> config)
    dynamicAccess.createInstanceFor[Instrumentation](instrumentation, dynamicAndConfigArg).recoverWith({
      case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[Instrumentation](instrumentation, configArg)
    }).recoverWith({
      case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[Instrumentation](instrumentation, Nil)
    }).get
  }

  /**
   * Access attached instrumentation by class. Returns null if there is no matching instrumentation.
   */
  def access[T <: Instrumentation](system: ActorSystem, instrumentationClass: Class[T]): T = system match {
    case actorSystem: ExtendedActorSystem ⇒ actorSystem.instrumentation.access(instrumentationClass)
    case _                                ⇒ null.asInstanceOf[T]
  }

  /**
   * Find attached instrumentation by class. Returns an Option.
   */
  def find[T <: Instrumentation](system: ActorSystem, instrumentationClass: Class[T]): Option[T] =
    Option(access(system, instrumentationClass))

  /**
   * Find attached instrumentation by implicit class tag. Returns an Option.
   */
  def find[T <: Instrumentation](system: ActorSystem)(implicit tag: ClassTag[T]): Option[T] =
    find(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Access attached instrumentation by class.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def apply[T <: Instrumentation](system: ActorSystem, instrumentationClass: Class[T]): T =
    access(system, instrumentationClass) match {
      case null            ⇒ throw new IllegalArgumentException(s"Trying to access non-existent instrumentation [$instrumentationClass]")
      case instrumentation ⇒ instrumentation
    }

  /**
   * Access attached instrumentation by implicit class tag.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def apply[T <: Instrumentation](system: ActorSystem)(implicit tag: ClassTag[T]): T =
    apply(system, tag.runtimeClass.asInstanceOf[Class[T]])

  /**
   * Check whether attached instrumentation exists, matching by class.
   */
  def exists[T <: Instrumentation](system: ActorSystem, instrumentationClass: Class[T]): Boolean =
    access(system, instrumentationClass) ne null

  /**
   * Java API: Access attached instrumentation by class.
   *
   * @throws IllegalArgumentException if there is no matching instrumentation
   */
  def get[T <: Instrumentation](system: ActorSystem, instrumentationClass: Class[T]): T =
    apply(system, instrumentationClass)

}

/**
 * Akka Instrumentation SPI.
 *
 * '''Important: instrumentation implementations must be thread-safe and non-blocking.'''
 *
 * There is optional context propagation available to instrumentations.
 * This can be used to transfer a trace identifier through a message flow, or similar.
 * In some implementations the context object will not be needed and can simply be null.
 *
 * A message flow will have the following calls:
 *
 *  - `actorTold` when the message is sent with `!` or `tell`, returns an optional context
 *  - `actorReceived` at the beginning of message processing, with optional context
 *  - `actorCompleted` at the end of message processing, with optional context
 *  - `clearContext` after message processing is complete
 */
abstract class Instrumentation {
  /**
   * Access this instrumentation by class.
   * Returns null if the instrumentation doesn't match.
   */
  def access[T <: Instrumentation](instrumentationClass: Class[T]): T

  /**
   * Record actor system started - after system initialisation and start.
   *
   * @param system the [[akka.actor.ActorSystem]] that has started
   */
  def systemStarted(system: ActorSystem): Unit

  /**
   * Record actor system shutdown - on system termination callback.
   *
   * '''Any instrumentation cleanup and shutdown can also happen at this point.'''
   *
   * @param system the [[akka.actor.ActorSystem]] that has shutdown
   */
  def systemShutdown(system: ActorSystem): Unit

  /**
   * Record actor created - before the actor is started in the context of the
   * creating thread. The actor isn't fully initialized yet, and you can't
   * send messages to it.
   *
   * FIXME: We can't easily get hold of the creating actor without changing a
   * lot in the akka code, but the call happens in the context of the creating
   * actor, so if there is a context attached it can be used to access
   * information.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being started
   */
  def actorCreated(actorRef: ActorRef): Unit

  /**
   * Record actor started.
   *
   * FIXME: There is no tracing context available when this method is called,
   * since we don't propagate contexts with system messages. Would also be
   * useful to provide who started the actor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being started
   */
  def actorStarted(actorRef: ActorRef): Unit

  /**
   * Record actor stopped.
   *
   * FIXME: There is no tracing context available when this method is called,
   * since we don't propagate contexts with system messages. Would also be
   * useful to provide who stopped the actor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being stopped
   */
  def actorStopped(actorRef: ActorRef): Unit

  /**
   * Record actor told - on message send with `!` or `tell`.
   *
   * @param actorRef the [[akka.actor.ActorRef]] being told the message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   * @return the context that will travel with this message and picked up by `actorReceived`
   */
  def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef

  /**
   * Record actor received - at the beginning of message processing.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   * @param context the context associated with this message
   */
  def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit

  /**
   * Record actor completed - at the end of message processing.
   *
   * @param actorRef the self [[akka.actor.ActorRef]] of the actor
   * @param message the message object
   * @param sender the sender [[akka.actor.Actor]] (may be dead letters)
   * @param context the context associated with this message
   */
  def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit

  /**
   * Clear the current context - when message context is no longer in scope.
   */
  def clearContext(): Unit

  /**
   * Record unhandled message - when the unhandled message is received.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that didn't handle the message
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record message going to dead letters - when the message is being sent.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that is no longer available
   * @param message the message object
   * @param sender the sender [[akka.actor.ActorRef]] (may be dead letters)
   */
  def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit

  /**
   * Record logging of warning - when the warning is being logged.
   * FIXME should we really have case classes here?
   *
   * @param actorRef the [[akka.actor.ActorRef]] logging the warning or Actor.noSender
   * @param warning the warning being logged
   */
  def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit

  /**
   * Record logging of error - when the error is being logged.
   * FIXME should we really have case classes here?
   *
   * @param actorRef the [[akka.actor.ActorRef]] logging the error or Actor.noSender
   * @param error the error being logged
   */
  def eventLogError(actorRef: ActorRef, error: Error): Unit

  /**
   * Record actor failure - before the failure is propagated to the supervisor.
   *
   * @param actorRef the [[akka.actor.ActorRef]] that has failed
   * @param cause the [[java.lang.Throwable]] cause of the failure
   */
  def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit
}

/**
 * Implementation of Instrumentation that does nothing by default. Select methods can be overridden.
 */
abstract class EmptyInstrumentation extends Instrumentation {
  override def access[T <: Instrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else null).asInstanceOf[T]

  override def systemStarted(system: ActorSystem): Unit = ()
  override def systemShutdown(system: ActorSystem): Unit = ()

  override def actorCreated(actorRef: ActorRef): Unit = ()
  override def actorStarted(actorRef: ActorRef): Unit = ()
  override def actorStopped(actorRef: ActorRef): Unit = ()

  override def actorTold(receiver: ActorRef, message: Any, sender: ActorRef): AnyRef = Instrumentation.EmptyContext
  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = ()
  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = ()

  override def clearContext(): Unit = ()

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = ()
  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = ()
  override def eventLogError(actorRef: ActorRef, error: Error): Unit = ()
  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = ()
}

/**
 * Final implementation of Instrumentation that does nothing.
 */
final class NoInstrumentation extends EmptyInstrumentation
