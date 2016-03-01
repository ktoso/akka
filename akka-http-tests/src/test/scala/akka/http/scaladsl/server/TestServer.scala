/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.actor.Actor.Receive
import akka.http.impl.engine.XTrace
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.model.headers.`X-Trace`
import akka.http.scaladsl.client._
import akka.util.Timeout
import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.{ Props, Actor, ActorSystem }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.pattern.ask
import scala.concurrent.duration._

object TestServer extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off
    """)

  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3.seconds)
  import system.dispatcher

  import Directives._
  import RequestBuilding._

  val workerActor = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case m ⇒ sender() ! "done!"
    }
  }))

  val mainActor = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case m ⇒ workerActor ! "help me"
    }
  }))

  // TODO: think if the HttpResponse should also carry around the X-Trace (we think yeah, make configurable)

  // format: OFF
  val bindingFuture = Http().bindAndHandle({
    path("complete") {
      println("Incoming, trace: " + XTrace.get)
      val res = Http().singleRequest(Get("http://127.0.0.1:1337/ask")) // shoud pick up the X-Trace
      res.map { r => println("singleRequest response trace: " + r.header[`X-Trace`])}

      complete("OK, trace: " + XTrace.getOrNew()) // internal, we'd get it from here in the `actorTold`
    } ~
    path("ask") {
      println("  Incoming [ask], trace: " + XTrace.get)

      complete((workerActor ? "help").mapTo[String])
    } ~
    path("ask-2") {
      complete((mainActor ? "help").mapTo[String])
    }
  }, interface = "localhost", port = 1337)
  // format: ON

  println(s"Server online at http://localhost:1337/\nPress RETURN to stop...")
  Console.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.terminate())

}
