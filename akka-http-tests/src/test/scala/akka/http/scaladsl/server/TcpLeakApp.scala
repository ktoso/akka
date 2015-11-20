/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, Uri }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

import java.net.InetSocketAddress
import com.typesafe.config.{ Config, ConfigFactory }
import akka.util.ByteString
import akka.actor.{ ActorSystemImpl, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Tcp, Sink, Source }

object TcpLeakApp extends App {
  val testConf: Config = ConfigFactory.parseString(
    """
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.io.tcp.trace-logging = off""")
  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val fm = ActorMaterializer()

  import system.dispatcher

  val tcpFlow = Tcp().outgoingConnection(new InetSocketAddress("127.0.0.1", 1234)).named("TCP")
  List
    .fill(100)(Source.single(ByteString("FOO")).via(tcpFlow).runWith(Sink.head))
    .last
    .onComplete {
      case error ⇒
        println(s"Error: $error")
        Thread.sleep(1000)
        println("===================== \n\n" + system.asInstanceOf[ActorSystemImpl].printTree + "\n\n========================")
    }

  readLine()
  system.shutdown()
}