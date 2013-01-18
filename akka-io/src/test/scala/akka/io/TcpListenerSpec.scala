/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.Socket
import scala.annotation.tailrec
import scala.concurrent.duration._
import org.scalatest.exceptions.TestFailedException
import akka.actor.{ Terminated, SupervisorStrategy, Actor, Props }
import akka.testkit.{ TestProbe, TestActorRef, AkkaSpec }
import Tcp._

class TcpListenerSpec extends AkkaSpec("akka.io.tcp.batch-accept-limit = 2") {

  "A TcpListener" must {

    "register its ServerSocketChannel with its selector" in new TestSetup {
      selector.expectMsgType[RegisterServerSocketChannel]
    }

    "let the Bind commander know when binding is completed" in new TestSetup {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    "accept acceptable connections and register them with its parent" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()
      attemptConnectionToEndpoint()

      // since the batch-accept-limit is 2 we must only receive 2 accepted connections
      listener ! ChannelAcceptable
      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
      parent.expectNoMsg(100.millis)

      // and pick up the last remaining connection on the next ChannelAcceptable
      listener ! ChannelAcceptable
      parent.expectMsgPF() { case RegisterIncomingConnection(_, `handlerRef`, Nil) ⇒ /* ok */ }
    }

    "react to Unbind commands by replying with Unbound and stopping itself" in new TestSetup {
      bindListener()

      val unbindCommander = TestProbe()
      unbindCommander.send(listener, Unbind)

      unbindCommander.expectMsg(Unbound)
      parent.expectMsgType[Terminated].actor must be(listener)
    }

    "drop an incoming connection if it cannot be registered with a selector" in new TestSetup {
      bindListener()

      attemptConnectionToEndpoint()

      listener ! ChannelAcceptable
      val channel = parent.expectMsgType[RegisterIncomingConnection].channel
      channel.isOpen must be(true)

      listener ! CommandFailed(RegisterIncomingConnection(channel, handler.ref, Nil))

      within(1.second) {
        channel.isOpen must be(false)
      }
    }
  }

  val counter = Iterator.from(0)

  class TestSetup {
    val selector = TestProbe()
    val handler = TestProbe()
    val handlerRef = handler.ref
    val bindCommander = TestProbe()
    val parent = TestProbe()
    val endpoint = TemporaryServerAddress()
    private val parentRef = TestActorRef(new ListenerParent)

    def bindListener() {
      listener ! Bound
      bindCommander.expectMsg(Bound)
    }

    def attemptConnectionToEndpoint(): Unit = new Socket(endpoint.getHostName, endpoint.getPort)

    def listener = parentRef.underlyingActor.listener

    private class ListenerParent extends Actor {
      val listener = context.actorOf(
        props = Props(new TcpListener(selector.ref, handler.ref, endpoint, 100, bindCommander.ref,
          Tcp(system).Settings, Nil)),
        name = "test-listener-" + counter.next())
      parent.watch(listener)
      def receive: Receive = {
        case msg ⇒ parent.ref forward msg
      }
      override def supervisorStrategy = SupervisorStrategy.stoppingStrategy
    }
  }

}
