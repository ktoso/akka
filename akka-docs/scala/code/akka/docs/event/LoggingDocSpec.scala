/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.event

import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.actor.Props

object LoggingDocSpec {

  //#my-actor
  import akka.event.Logging

  class MyActor extends Actor {
    val log = Logging(context.system, this)
    override def preStart() = {
      log.debug("Starting")
    }
    override def preRestart(reason: Throwable, message: Option[Any]) {
      log.error(reason, "Restarting due to [{}] when processing [{}]",
        reason.getMessage, message.getOrElse(""))
    }
    def receive = {
      case "test" ⇒ log.info("Received test")
      case x      ⇒ log.warning("Received unknown message: {}", x)
    }
  }
  //#my-actor

  //#my-event-listener
  import akka.event.Logging.InitializeLogger
  import akka.event.Logging.LoggerInitialized
  import akka.event.Logging.Error
  import akka.event.Logging.Warning
  import akka.event.Logging.Info
  import akka.event.Logging.Debug

  class MyEventListener extends Actor {
    def receive = {
      case InitializeLogger(_)                        ⇒ sender ! LoggerInitialized
      case Error(cause, logSource, logClass, message) ⇒ // ...
      case Warning(logSource, logClass, message)      ⇒ // ...
      case Info(logSource, logClass, message)         ⇒ // ...
      case Debug(logSource, logClass, message)        ⇒ // ...
    }
  }
  //#my-event-listener

}

class LoggingDocSpec extends AkkaSpec {

  import LoggingDocSpec.MyActor

  "use a logging actor" in {
    val myActor = system.actorOf(Props(new MyActor))
    myActor ! "test"
  }

  "allow registration to dead letters" in {
    //#deadletters
    import akka.actor.{ Actor, DeadLetter, Props }

    val listener = system.actorOf(Props(new Actor {
      def receive = {
        case d: DeadLetter ⇒ println(d)
      }
    }))
    system.eventStream.subscribe(listener, classOf[DeadLetter])
    //#deadletters
  }

}
