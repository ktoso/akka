/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor._
import akka.testkit._
import akka.testkit.TestActors.EchoActor
import com.typesafe.config.{ Config, ConfigFactory }

object InstrumentationSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.CountInstrumentation"]
    akka.count-instrumentation.dispatcher-id = "akka.actor.default-dispatcher"
    akka.diagnostics.checker.disabled-checks = ["typo"]
  """)

  val printConfig: Config = ConfigFactory.parseString("""
    akka.instrumentations = ["akka.instrument.PrintInstrumentation", "akka.instrument.CountInstrumentation"]
    akka.count-instrumentation.dispatcher-id = "akka.actor.default-dispatcher"
    akka.print-instrumentation.muted = true
    akka.diagnostics.checker.disabled-checks = ["typo"]
  """)
}

abstract class AbstractInstrumentationSpec(_config: Config) extends AkkaSpec(_config) with ImplicitSender with DefaultTimeout {
  val instrumentation = CountInstrumentation(system)

  "Actor tracing" must {
    "instrument actor system start" in {
      instrumentation.counts.systemStarted.get should be(1)
      instrumentation.counts.dispatcherStarted.get should be(1)
    }

    "instrument actor lifecycle" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case message ⇒
            sender ! message
            context.stop(self)
        }
      }))
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorCreated.get should be(1)
      instrumentation.counts.actorStarted.get should be(1)
      watch(actor)
      expectTerminated(actor)
      instrumentation.counts.actorShutdown.get should be(1)
    }

    "instrument actor stash and unstash" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props[StashActor])
      watch(actor)
      actor ! "stash"
      actor ! "unstash"
      expectTerminated(actor)
      instrumentation.counts.actorStashed.get should be(1)
      instrumentation.counts.actorUnstashed.get should be(1)
    }

    "instrument actor unstashAll" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props[StashActor])
      watch(actor)
      1 to 5 foreach { _ ⇒ actor ! "stash" }
      actor ! "all"
      expectTerminated(actor)
      instrumentation.counts.actorStashed.get should be(5)
      instrumentation.counts.actorUnstashed.get should be(5)
    }

    "instrument actor messages" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new EchoActor))
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorTold.get should be(2)
      instrumentation.counts.actorReceived.get should be(2)
      actor ! "message"
      expectMsg("message")
      instrumentation.counts.actorTold.get should be(4)
      instrumentation.counts.actorReceived.get should be(4)
      // actor completed for last message may not have been recorded yet
      instrumentation.counts.actorCompleted.get should be >= 2L
    }

    "instrument events" in {
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new Actor with ActorLogging {
        def receive = {
          case "echo" ⇒
            sender ! "echo"
          case "dead letter" ⇒
            system.deadLetters ! "A dead letter"; sender ! "dead letter"
          case "warning" ⇒
            log.warning("A warning"); sender ! "warning"
          case "error" ⇒
            log.error("An error"); sender ! "error"
          case "failure" ⇒ throw new IllegalStateException("failure")
          case "stop"    ⇒ context.stop(self)
        }
      }))
      actor ! "dead letter"
      expectMsg("dead letter")
      instrumentation.counts.eventDeadLetter.get should be(1)
      actor ! "warning"
      expectMsg("warning")
      instrumentation.counts.eventLogWarning.get should be(1)
      actor ! "error"
      expectMsg("error")
      instrumentation.counts.eventLogError.get should be(1)
      actor ! "unhandled"
      actor ! "echo"
      expectMsg("echo")
      instrumentation.counts.eventUnhandled.get should be(1)
      actor ! "failure"
      actor ! "echo"
      expectMsg("echo")
      instrumentation.counts.eventActorFailure.get should be(1)
      watch(actor)
      actor ! "stop"
      expectTerminated(actor)
    }

    "instrument actor scheduling" in {
      val entriesStart = instrumentation.counts.dispatcherEntries.get
      instrumentation.counts.reset()
      val actor = system.actorOf(Props(new Actor {
        def receive = {
          case "ping" ⇒
            sender ! "pong"
          case "stop" ⇒
            context.stop(self)
        }
      }))
      actor ! "ping"
      expectMsg("pong")
      (instrumentation.counts.dispatcherEntries.get - entriesStart) should be(1)
      instrumentation.counts.actorScheduled.get.toInt should be > 0
      instrumentation.counts.actorRunning.get.toInt should be > 0
      // sleep here a bit so that the actor can become idle
      Thread.sleep(100)
      instrumentation.counts.actorIdle.get.toInt should be > 0
      watch(actor)
      actor ! "stop"
      expectTerminated(actor)
    }

    "instrument actor system shutdown" in {
      instrumentation.counts.reset()
      system.shutdown()
      system.awaitTermination(timeout.duration)
      instrumentation.counts.systemShutdown.get should be(1)
    }
  }
}

class InstrumentationSpec extends AbstractInstrumentationSpec(InstrumentationSpec.testConfig)

class InstrumentationPrintSpec extends AbstractInstrumentationSpec(InstrumentationSpec.printConfig)

class StashActor extends Actor with Stash {
  def receive = {
    case "stash" ⇒ stash()
    case "unstash" ⇒
      unstash()
      context.stop(self)
    case "all" ⇒
      unstashAll()
      context.stop(self)
    case _ ⇒
  }
}
