/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{ PushStage, Directive, Context }
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.ByteString
import cookbook.RecipeParseLines
import docs.utils.TestUtils
import StreamTcp._

import scala.concurrent.Future

class StreamTcpDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher
  implicit val mat = ActorFlowMaterializer()

  // silence sysout
  def println(s: String) = ()

  "simple server connection" in {
    {
      //#echo-server-simple-bind
      val localhost = new InetSocketAddress("127.0.0.1", 8888)
      //#echo-server-simple-bind
    }
    {
      val localhost = TestUtils.temporaryServerAddress()
      //#echo-server-simple-bind
      val connections: Source[IncomingConnection, Future[ServerBinding]] =
        StreamTcp().bind(localhost)
      //#echo-server-simple-bind

      //#echo-server-simple-handle
      connections runForeach { connection =>
        println(s"New connection from: ${connection.remoteAddress}")

        val echo = Flow[ByteString]
          .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
          .map(_ + "!!!\n")
          .map(ByteString(_))

        connection.handleWith(echo)
      }
      //#echo-server-simple-handle
    }
  }

  "initial server banner echo server" in {
    val localhost = TestUtils.temporaryServerAddress()
    val connections = StreamTcp().bind(localhost)
    val serverProbe = TestProbe()

    //#welcome-banner-chat-server
    connections runForeach { connection =>

      val serverLogic = Flow() { implicit b =>
        import FlowGraph.Implicits._

        // server logic, parses incoming commands
        val commandParser = new PushStage[String, String] {
          override def onPush(elem: String, ctx: Context[String]): Directive = {
            elem match {
              case "BYE" ⇒ ctx.finish()
              case _     ⇒ ctx.push(elem + "!")
            }
          }
        }

        import connection._
        val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!\n"

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = b.add(Flow[ByteString]
          .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
          //#welcome-banner-chat-server
          .map { command ⇒ serverProbe.ref ! command; command }
          //#welcome-banner-chat-server
          .transform(() ⇒ commandParser)
          .map(_ + "\n")
          .map(ByteString(_)))

        val concat = b.add(Concat[ByteString]())
        // first we emit the welcome message,
        welcome ~> concat.in(0)
        // then we continue using the echo-logic Flow
        echo.outlet ~> concat.in(1)

        (echo.inlet, concat.out)
      }

      connection.handleWith(serverLogic)
    }

    //#welcome-banner-chat-server

    val input = new AtomicReference("Hello world" :: "What a lovely day" :: Nil)
    def readLine(prompt: String): String = {
      input.get() match {
        case all @ cmd :: tail if input.compareAndSet(all, tail) ⇒ cmd
        case _ ⇒ "q"
      }
    }

    //#repl-client
    val connection = StreamTcp().outgoingConnection(localhost)

    val replParser = new PushStage[String, ByteString] {
      override def onPush(elem: String, ctx: Context[ByteString]): Directive = {
        elem match {
          case "q" ⇒ ctx.pushAndFinish(ByteString("BYE\n"))
          case _   ⇒ ctx.push(ByteString(s"$elem\n"))
        }
      }
    }

    val repl = Flow[ByteString]
      .transform(() => RecipeParseLines.parseLines("\n", maximumLineBytes = 256))
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .transform(() ⇒ replParser)

    connection.join(repl).run()
    //#repl-client

    serverProbe.expectMsg("Hello world")
    serverProbe.expectMsg("What a lovely day")
    serverProbe.expectMsg("BYE")
  }
}
