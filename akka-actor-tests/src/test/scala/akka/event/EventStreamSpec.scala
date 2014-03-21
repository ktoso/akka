/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import language.postfixOps

import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import akka.event.Logging.InitializeLogger
import akka.pattern.gracefulStop
import akka.testkit.{ EventFilter, TestEvent, TestProbe, AkkaSpec }

object EventStreamSpec {

  val config = ConfigFactory.parseString("""
      akka {
        actor.serialize-messages = off
        stdout-loglevel = WARNING
        loglevel = INFO
        loggers = ["akka.event.EventStreamSpec$MyLog", "%s"]
      }
      """.format(Logging.StandardOutLogger.getClass.getName))

  val configUnhandled = ConfigFactory.parseString("""
      akka {
        actor.serialize-messages = off
        stdout-loglevel = WARNING
        loglevel = WARNING
        actor.debug.unhandled = on
      }
      """)

  val configUnhandledWithDebug =
    ConfigFactory.parseString("akka.actor.debug.event-stream = on")
      .withFallback(configUnhandled)

  final case class M(i: Int)

  final case class SetTarget(ref: ActorRef)

  class MyLog extends Actor {
    var dst: ActorRef = context.system.deadLetters
    def receive = {
      case Logging.InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        bus.subscribe(context.self, classOf[UnhandledMessage])
        sender() ! Logging.LoggerInitialized
      case SetTarget(ref)      ⇒ { dst = ref; dst ! "OK" }
      case e: Logging.LogEvent ⇒ dst ! e
      case u: UnhandledMessage ⇒ dst ! u
    }
  }

  // class hierarchy for subchannel test
  class A
  class B1 extends A
  class B2 extends A
  class C extends B1

  trait T
  trait AT extends T
  trait ATT extends AT
  trait BT extends T
  trait BTT extends BT
  class CC
  class CCATBT extends CC with ATT with BTT
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class EventStreamSpec extends AkkaSpec(EventStreamSpec.config) {

  import EventStreamSpec._

  val impl = system.asInstanceOf[ActorSystemImpl]

  "An EventStream" must {

    "manage subscriptions" in {
      val bus = new EventStream(true)
      bus.subscribe(testActor, classOf[M])
      bus.publish(M(42))
      within(1 second) {
        expectMsg(M(42))
        bus.unsubscribe(testActor)
        bus.publish(M(13))
        expectNoMsg
      }
    }

    "not allow null as subscriber" in {
      val bus = new EventStream(true)
      intercept[IllegalArgumentException] { bus.subscribe(null, classOf[M]) }.getMessage should be("subscriber is null")
    }

    "not allow null as unsubscriber" in {
      val bus = new EventStream(true)
      intercept[IllegalArgumentException] { bus.unsubscribe(null, classOf[M]) }.getMessage should be("subscriber is null")
      intercept[IllegalArgumentException] { bus.unsubscribe(null) }.getMessage should be("subscriber is null")
    }

    "be able to log unhandled messages" in {
      val sys = ActorSystem("EventStreamSpecUnhandled", configUnhandled)
      try {
        sys.eventStream.subscribe(testActor, classOf[AnyRef])
        val m = UnhandledMessage(42, sys.deadLetters, sys.deadLetters)
        sys.eventStream.publish(m)
        expectMsgAllOf(m, Logging.Debug(sys.deadLetters.path.toString, sys.deadLetters.getClass, "unhandled message from " + sys.deadLetters + ": 42"))
        sys.eventStream.unsubscribe(testActor)
      } finally {
        shutdown(sys)
      }
    }

    "manage log levels" in {
      val bus = new EventStream(false)
      bus.startDefaultLoggers(impl)
      bus.publish(SetTarget(testActor))
      expectMsg("OK")
      within(2 seconds) {
        import Logging._
        verifyLevel(bus, InfoLevel)
        bus.setLogLevel(WarningLevel)
        verifyLevel(bus, WarningLevel)
        bus.setLogLevel(DebugLevel)
        verifyLevel(bus, DebugLevel)
        bus.setLogLevel(ErrorLevel)
        verifyLevel(bus, ErrorLevel)
      }
    }

    "manage sub-channels using classes" in {
      val a = new A
      val b1 = new B1
      val b2 = new B2
      val c = new C
      val bus = new EventStream(false)
      within(2 seconds) {
        bus.subscribe(testActor, classOf[B2]) should be(true)
        bus.publish(c)
        bus.publish(b2)
        expectMsg(b2)
        bus.subscribe(testActor, classOf[A]) should be(true)
        bus.publish(c)
        expectMsg(c)
        bus.publish(b1)
        expectMsg(b1)
        bus.unsubscribe(testActor, classOf[B1]) should be(true)
        bus.publish(c)
        bus.publish(b2)
        bus.publish(a)
        expectMsg(b2)
        expectMsg(a)
        expectNoMsg
      }
    }

    "manage sub-channels using classes and traits (update on subscribe)" in {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.subscribe(a2.ref, classOf[BT]) should be(true)
      es.subscribe(a3.ref, classOf[CC]) should be(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should be(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.expectMsgType[AT] should be(tm2)
      a2.expectMsgType[BT] should be(tm2)
      a3.expectMsgType[CC] should be(tm1)
      a3.expectMsgType[CC] should be(tm2)
      a4.expectMsgType[CCATBT] should be(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BT]) should be(true)
      es.unsubscribe(a3.ref, classOf[CC]) should be(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should be(true)
    }

    "manage sub-channels using classes and traits (update on unsubscribe)" in {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.subscribe(a2.ref, classOf[BT]) should be(true)
      es.subscribe(a3.ref, classOf[CC]) should be(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should be(true)
      es.unsubscribe(a3.ref, classOf[CC]) should be(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.expectMsgType[AT] should be(tm2)
      a2.expectMsgType[BT] should be(tm2)
      a3.expectNoMsg(1 second)
      a4.expectMsgType[CCATBT] should be(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BT]) should be(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should be(true)
    }

    "manage sub-channels using classes and traits (update on unsubscribe all)" in {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3, a4 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.subscribe(a2.ref, classOf[BT]) should be(true)
      es.subscribe(a3.ref, classOf[CC]) should be(true)
      es.subscribe(a4.ref, classOf[CCATBT]) should be(true)
      es.unsubscribe(a3.ref)
      es.publish(tm1)
      es.publish(tm2)
      a1.expectMsgType[AT] should be(tm2)
      a2.expectMsgType[BT] should be(tm2)
      a3.expectNoMsg(1 second)
      a4.expectMsgType[CCATBT] should be(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BT]) should be(true)
      es.unsubscribe(a4.ref, classOf[CCATBT]) should be(true)
    }

    "manage sub-channels using classes and traits (update on publish)" in {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.subscribe(a2.ref, classOf[BT]) should be(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.expectMsgType[AT] should be(tm2)
      a2.expectMsgType[BT] should be(tm2)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BT]) should be(true)
    }

    "manage sub-channels using classes and traits (unsubscribe classes used with trait)" in {
      val es = new EventStream(false)
      val tm1 = new CC
      val tm2 = new CCATBT
      val a1, a2, a3 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.subscribe(a2.ref, classOf[BT]) should be(true)
      es.subscribe(a2.ref, classOf[CC]) should be(true)
      es.subscribe(a3.ref, classOf[CC]) should be(true)
      es.unsubscribe(a2.ref, classOf[CC]) should be(true)
      es.unsubscribe(a3.ref, classOf[CCATBT]) should be(true)
      es.publish(tm1)
      es.publish(tm2)
      a1.expectMsgType[AT] should be(tm2)
      a2.expectMsgType[BT] should be(tm2)
      a3.expectMsgType[CC] should be(tm1)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BT]) should be(true)
      es.unsubscribe(a3.ref, classOf[CC]) should be(true)
    }

    "manage sub-channels using classes and traits (subscribe after publish)" in {
      val es = new EventStream(false)
      val tm1 = new CCATBT
      val a1, a2 = TestProbe()

      es.subscribe(a1.ref, classOf[AT]) should be(true)
      es.publish(tm1)
      a1.expectMsgType[AT] should be(tm1)
      a2.expectNoMsg(1 second)
      es.subscribe(a2.ref, classOf[BTT]) should be(true)
      es.publish(tm1)
      a1.expectMsgType[AT] should be(tm1)
      a2.expectMsgType[BTT] should be(tm1)
      es.unsubscribe(a1.ref, classOf[AT]) should be(true)
      es.unsubscribe(a2.ref, classOf[BTT]) should be(true)
    }

    "unsubscribe an actor on its termination" in {
      val sys = ActorSystem("EventStreamSpecUnsubscribeOnTerminated", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()
        val tm = new A

        val target = sys.actorOf(Props(new Actor {
          def receive = { case in ⇒ a1.ref forward in }
        }), "to-be-killed")

        es.subscribe(a2.ref, classOf[Any])
        es.subscribe(target, classOf[A]) should be(true)
        es.subscribe(target, classOf[A]) should be(false)

        target ! PoisonPill
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")
        fishForDebugMessage(a2, s"unwatching $target")

        es.publish(tm)

        a1.expectNoMsg(1 second)
        a2.expectMsg(tm)
      } finally {
        sys.shutdown()
      }
    }

    "unsubscribe the actor, when it subscribes already in terminated state" in {
      val sys = ActorSystem("EventStreamSpecUnsubscribeTerminated", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        val target = system.actorOf(Props(new Actor {
          def receive = { case in ⇒ a1.ref forward in }
        }), "to-be-killed")

        watch(target)
        target ! PoisonPill
        expectTerminated(target)

        es.subscribe(a2.ref, classOf[Any])

        // target1 is Terminated; When subscribing, it will be unsubscribed by the Unsubscriber right away
        es.subscribe(target, classOf[A]) should be(true)
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")

        es.subscribe(target, classOf[A]) should be(true)
        fishForDebugMessage(a2, s"unsubscribing $target from all channels")
      } finally {
        sys.shutdown()
      }
    }

    "not allow initializing a TerminatedUnsubscriber twice" in {
      val sys = ActorSystem("MustNotAllowDoubleInitOfTerminatedUnsubscriber", config)
      // initializes an TerminatedUnsubscriber during start

      try {
        val es = sys.eventStream
        val p = TestProbe()

        val refWillBeUsedAsUnsubscriber = es.initUnsubscriber(p.ref)

        refWillBeUsedAsUnsubscriber should equal(false)

      } finally {
        sys.shutdown()
      }
    }

    "unwatch an actor from unsubscriber when that actor unsubscribes from the stream" in {
      val sys = ActorSystem("MustUnregisterDuringUnsubscribe", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        es.subscribe(a1.ref, classOf[Logging.Debug])

        es.subscribe(a2.ref, classOf[A])
        fishForDebugMessage(a1, s"watching ${a2.ref}")

        es.unsubscribe(a2.ref)
        fishForDebugMessage(a1, s"unwatching ${a2.ref}")

      } finally {
        sys.shutdown()
      }
    }

    "unwatch an actor from unsubscriber when that actor unsubscribes from channels it subscribed" in {
      val sys = ActorSystem("MustUnregisterWhenNoMoreChannelSubscriptions", configUnhandledWithDebug)

      try {
        val es = sys.eventStream
        val a1, a2 = TestProbe()

        es.subscribe(a1.ref, classOf[Logging.Debug])

        es.subscribe(a2.ref, classOf[A])
        es.subscribe(a2.ref, classOf[T])
        fishForDebugMessage(a1, s"watching ${a2.ref}")

        es.unsubscribe(a2.ref, classOf[A]) should equal(true)
        fishForDebugMessage(a1, s"unsubscribing ${a2.ref} from channel class akka.event.EventStreamSpec$$A")
        a1.expectNoMsg(1 second)

        a1.expectNoMsg(1 second)

        es.unsubscribe(a2.ref, classOf[T]) should equal(true)
        fishForDebugMessage(a1, s"subscriber ${a2.ref} has now 0 channel subscriptions")
        fishForDebugMessage(a1, s"unsubscribing ${a2.ref} from channel interface akka.event.EventStreamSpec$$T")
        fishForDebugMessage(a1, s"unwatching ${a2.ref}")
        a1.expectNoMsg(1 second)

        es.unsubscribe(a2.ref, classOf[T]) should equal(false)

      } finally {
        sys.shutdown()
      }
    }

  }

  private def verifyLevel(bus: LoggingBus, level: Logging.LogLevel) {
    import Logging._
    val allmsg = Seq(Debug("", null, "debug"), Info("", null, "info"), Warning("", null, "warning"), Error("", null, "error"))
    val msg = allmsg filter (_.level <= level)
    allmsg foreach bus.publish
    msg foreach (expectMsg(_))
  }

  private def fishForDebugMessage(a: TestProbe, messagePrefix: String) {
    a.fishForMessage(3 seconds, hint = "expected debug message prefix: " + messagePrefix) {
      case Logging.Debug(_, _, msg: String) if msg startsWith messagePrefix ⇒ true
      case other ⇒ false
    }
  }

}
