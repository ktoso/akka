/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.dispatch.japi
import akka.event.{ DummyClassForStringSources, Logging }
import akka.stream._
import akka.stream.impl.fusing.Log
import akka.stream.scaladsl._
import akka.stream.testkit.{ AkkaSpec, ScriptedTest }
import akka.testkit.{ EventFilter, TestProbe }

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import scala.util.control.NoStackTrace

class FlowLogSpec extends AkkaSpec("akka.loglevel = DEBUG") with ScriptedTest {

  implicit val mat: FlowMaterializer = ActorFlowMaterializer()

  def logProbe(): TestProbe = {
    val p = TestProbe()
    system.eventStream.subscribe(p.ref, classOf[Logging.LogEvent])
    p
  }

  "A Log" must {

    val LogSrc = s"akka.stream.Log(akka://${Logging.simpleName(classOf[FlowLogSpec])})"
    val LogClazz = classOf[DummyClassForStringSources]

    "on Flow" must {

      "debug each element" in {
        val p = logProbe()

        val debugging = Flow[Int].log("my-debug")
        Source(1 to 2).via(debugging).runWith(Sink.ignore)

        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-debug] Element: 1"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-debug] Element: 2"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[my-debug] Upstream finished."))
      }

    }

    "on javadsl.Flow" must {
      "debug each element" in {
        val p = logProbe()

        val log = Logging(system, "com.example.ImportantLogger")

        val debugging: javadsl.Flow[Integer, Integer, Unit] = javadsl.Flow.of(classOf[Integer])
          .log("log-1")
          .log("log-2", new javadsl.japi.Function[Integer, Integer] { def apply(i: Integer) = i })
          .log("log-3", new javadsl.japi.Function[Integer, Integer] { def apply(i: Integer) = i }, log)
          .log("log-4", log)

        javadsl.Source.single[Integer](1).via(debugging).runWith(javadsl.Sink.ignore(), mat)

        var counter = 1
        import scala.concurrent.duration._
        p.fishForMessage(3.seconds) {
          case Logging.Debug(_, _, msg: String) if msg contains "Element: 1" ⇒
            counter += 1
            counter == 4

          case Logging.Debug(_, _, msg: String) if msg contains "Upstream finished" ⇒
            false
        }
      }
    }

    "on Source" must {
      "debug each element" in {
        val p = logProbe()

        Source(1 to 2).log("flow-2").runWith(Sink.ignore)

        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-2] Element: 1"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-2] Element: 2"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-2] Upstream finished."))
      }

      "allow extracting value to be logged" in {
        val p = logProbe()

        case class Complex(a: Int, b: String)
        Source.single(Complex(1, "42")).log("flow-3", _.b).runWith(Sink.ignore)

        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-3] Element: 42"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-3] Upstream finished."))
      }

      "log upstream failure" in {
        val p = logProbe()

        val cause = new TestException
        Source.failed(cause).log("flow-4").runWith(Sink.ignore)
        p.expectMsg(Logging.Error(cause, LogSrc, LogClazz, "[flow-4] Upstream failed."))
      }

      "allow passing in custom LoggingAdapter" in {
        val p = logProbe()
        val log = Logging(system, "com.example.ImportantLogger")

        Source.single(42).log("flow-5")(log).runWith(Sink.ignore)

        val src = "com.example.ImportantLogger(akka://FlowLogSpec)"
        val clazz = classOf[DummyClassForStringSources]
        p.expectMsg(Logging.Debug(src, clazz, "[flow-5] Element: 42"))
        p.expectMsg(Logging.Debug(src, clazz, "[flow-5] Upstream finished."))
      }

      "allow configuring log levels via OperationAttributes" in {
        val p = logProbe()

        val logAttrs = OperationAttributes.logLevels(
          onElement = Logging.WarningLevel,
          onFinish = Logging.InfoLevel,
          onFailure = Logging.DebugLevel)

        Source.single(42)
          .log("flow-6")
          .withAttributes(OperationAttributes.logLevels(
            onElement = Logging.WarningLevel,
            onFinish = Logging.InfoLevel,
            onFailure = Logging.DebugLevel))
          .runWith(Sink.ignore)

        p.expectMsg(Logging.Warning(LogSrc, LogClazz, "[flow-6] Element: 42"))
        p.expectMsg(Logging.Info(LogSrc, LogClazz, "[flow-6] Upstream finished."))

        val cause = new TestException
        Source.failed(cause)
          .log("flow-6e")
          .withAttributes(logAttrs)
          .runWith(Sink.ignore)
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[flow-6e] Upstream failed, cause: FlowLogSpec$TestException: Boom!"))
      }
    }

    "on javadsl.Source" must {
      "debug each element" in {
        val p = logProbe()

        val log = Logging(system, "com.example.ImportantLogger")

        javadsl.Source.single[Integer](1)
          .log("log-1")
          .log("log-2", new javadsl.japi.Function[Integer, Integer] { def apply(i: Integer) = i })
          .log("log-3", new javadsl.japi.Function[Integer, Integer] { def apply(i: Integer) = i }, log)
          .log("log-4", log)
          .runWith(javadsl.Sink.ignore(), mat)

        var counter = 1
        import scala.concurrent.duration._
        p.fishForMessage(3.seconds) {
          case Logging.Debug(_, _, msg: String) if msg contains "Element: 1" ⇒
            counter += 1
            counter == 4

          case Logging.Debug(_, _, msg: String) if msg contains "Upstream finished" ⇒
            false
        }
      }
    }

    "in a nested Flow" must {

      // TODO how can I expose information that X is nested in Y?
      "debug each element" in {
        val p = logProbe()

        val debugging = Flow[Int].log("pre-map")

        val f = Flow() { implicit b ⇒
          import FlowGraph.Implicits._
          val zip = b.add(Zip[String, Int]())
          Source(1 to 2) ~> debugging ~> Flow[Int].map(_.toString) ~> zip.in0
          zip.in1 → zip.out
        }.named("outer")

        f.runWith(Source(1 to 2), Sink.ignore)

        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[pre-map] Element: 1"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[pre-map] Element: 2"))
        p.expectMsg(Logging.Debug(LogSrc, LogClazz, "[pre-map] Upstream finished."))
      }

    }

  }

  final class TestException extends RuntimeException("Boom!") with NoStackTrace

}
