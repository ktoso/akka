/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}

/**
 * A collection of common actor patterns used in tests.
 */
object TestActors {

  import scala.concurrent.duration._
  /**
   * EchoActor sends back received messages (unmodified).
   */
  class EchoActor extends Actor with ActorLogging{


    /**
     * Sometimes Nakadi is not stable, and can return errors for valid token.
     * We want to try connecting several times before going for backoff
     */
    def supervisionStrategy = OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 10.seconds) {
      case e: Exception =>
        log.warning("exception in fulfillment api producer {}", e.getMessage)
        SupervisorStrategy.Restart
      case _ => SupervisorStrategy.Escalate
    }


    override def receive = {
      case message ⇒ sender() ! message
    }
  }

  /**
   * BlackholeActor does nothing for incoming messages, its like a blackhole.
   */
  class BlackholeActor extends Actor {
    override def receive = {
      case _ ⇒ // ignore...
    }
  }

  /**
   * ForwardActor forwards all messages as-is to specified ActorRef.
   *
   * @param ref target ActorRef to forward messages to
   */
  class ForwardActor(ref: ActorRef) extends Actor {
    override def receive = {
      case message ⇒ ref forward message
    }
  }

  val echoActorProps = Props[EchoActor]()
  val blackholeProps = Props[BlackholeActor]()
  def forwardActorProps(ref: ActorRef) = Props(classOf[ForwardActor], ref)

}
