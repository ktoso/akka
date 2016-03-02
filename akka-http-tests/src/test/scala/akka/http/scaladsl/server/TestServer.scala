/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.Actor.Receive
import akka.http.impl.engine.XTrace
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.headers.`X-Trace`
import akka.http.scaladsl.client._
import akka.util.Timeout
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.{ Actor, Props, ActorSystem }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import scala.concurrent.forkjoin.ThreadLocalRandom._
import Protocol._

object TestServer extends App with Directives with RequestBuilding {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)

  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3.seconds)
  import system.dispatcher

  val master = system.actorOf(Master.props, "master")

  // format: OFF
  val routes = {
    path("complete") {
      println("Incoming, trace: " + XTrace.get)

      val res = Http().singleRequest(Get("http://127.0.0.1:1337/ask")) // shoud pick up the X-Trace
      res.map { r => println("singleRequest response trace: " + r.header[`X-Trace`])}

      complete("OK, trace: " + XTrace.getOrNew()) // internal, we'd get it from here in the `actorTold`
    } ~
    path("ask") {
      println("  Incoming [ask], trace: " + XTrace.get)

      complete((master ? "help").mapTo[String])
    }
  }

  // format: ON
  val bindingFuture = Http().bindAndHandle(routes, interface = "localhost", port = 1337)

  println(s"Server online at http://localhost:1337/\nPress RETURN to stop...")
  Console.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.terminate())

}

object Protocol {
  final case class IncomingRequest(s: String)
  final case class InnerThingy(s: String)
  final case class WorkCompleted(s: String)
}

object Master {
  def props = Props(new Master)
}
class Master extends Actor {
  override def receive: Receive = {
    case WorkCompleted(name) ⇒

    case m ⇒
      Thread.sleep(random.nextInt(100) + 1)
      sender() ! "ok, working on it!"

      Thread.sleep(random.nextInt(100) + 1)
      context.actorOf(Worker.props) ! InnerThingy(m.toString)
  }
}

object Worker {
  def props = Props(new Worker)
}
class Worker extends Actor {
  override def receive = {
    case x ⇒
      Thread.sleep(random.nextInt(100) + 1)
      sender() ! WorkCompleted(x.toString)
  }
}