/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.dispatch.MessageDispatcher
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, Uri }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

object ConnectionTestApp {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off

    akka.http {
      client {
        idle-timeout = 10s
      }
    }
    """)

  implicit val system = ActorSystem("ConnectionTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val clientFlow = Http().superPool[Int]()
  val sourceActor = {
    // Our superPool expects (HttpRequest, Int) as input
    val source = Source.actorRef[(HttpRequest, Int)](10000, OverflowStrategy.dropNew).buffer(20000, OverflowStrategy.fail)
    //    val sink = Sink.head[(Try[HttpResponse], Int)] /* causes:
    //      [ERROR] [11/18/2015 02:22:03.981] [ConnectionTest-akka.actor.default-dispatcher-10] [akka://ConnectionTest/user/SlotProcessor-1] onComplete must only be called once
    //        at java.lang.IllegalStateException: onComplete must only be called once
    //        at akka.stream.actor.ActorPublisher$class.onComplete(ActorPublisher.scala:201)
    //        at akka.http.impl.engine.client.PoolSlot$SlotProcessor.onComplete(PoolSlot.scala:256)
    //    */
    val sink = Sink.foreach[(Try[HttpResponse], Int)] {
      case (resp, id) ⇒ handleResponse(resp, id)
    }
    source.map(r ⇒ { println(Console.YELLOW + "Delivering to client flow: " + r + Console.RESET); r }).via(clientFlow).to(sink).run()
  }

  def sendPoolFlow(uri: Uri, id: Int): Unit = {
    sourceActor ! ((buildRequest(uri), id))
  }

  def sendPoolFuture(uri: Uri, id: Int): Unit = {
    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(buildRequest(uri))

    responseFuture.onComplete(r ⇒ handleResponse(r, id))
  }

  def sendSingle(uri: Uri, id: Int): Unit = {
    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection(uri.authority.host.address, uri.authority.port)
    val responseFuture: Future[HttpResponse] =
      Source.single(buildRequest(uri))
        .via(connectionFlow)
        .runWith(Sink.head)

    responseFuture.onComplete(r ⇒ handleResponse(r, id))
  }

  private def buildRequest(uri: Uri): HttpRequest =
    HttpRequest(uri = uri)

  private def handleResponse(httpResp: Try[HttpResponse], id: Int): Unit = {
    httpResp match {
      case Success(httpRes) ⇒
        println(s"$id: OK (${httpRes.status.intValue})")
        httpRes.entity.dataBytes.runWith(Sink.ignore)

      case Failure(ex) ⇒
        println(s"$id: $ex")
    }
  }

  def main(args: Array[String]): Unit = {
    for (i ← 1 to 10000) {
      val u = s"http://127.0.0.1:6666/test/$i"
      println(Console.RED + "u =>" + u + Console.RESET)
      sendPoolFlow(Uri(u), i)
      //sendPoolFuture(uri, i)
      //sendSingle(uri, i)
    }

    readLine()
    MessageDispatcher.printActors()
    readLine()
    system.shutdown()
  }

}
