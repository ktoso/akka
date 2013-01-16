/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.SocketChannel
import scala.collection.immutable
import akka.actor.ActorRef
import Tcp._

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 */
class TcpOutgoingConnection(_selector: ActorRef,
                            commander: ActorRef,
                            remoteAddress: InetSocketAddress,
                            localAddress: Option[InetSocketAddress],
                            options: immutable.Seq[SocketOption])
  extends TcpConnection(_selector, SocketChannel.open()) {
  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))

  log.debug("Attempting connection to {}", remoteAddress)
  if (channel.connect(remoteAddress))
    completeConnect(commander, options)
  else {
    selector ! RegisterClientChannel(channel)
    context.become(connecting(commander, options))
  }

  def receive: Receive = PartialFunction.empty

  def connecting(commander: ActorRef, options: immutable.Seq[SocketOption]): Receive = {
    case ChannelConnectable ⇒
      try {
        val connected = channel.finishConnect()
        assert(connected, "Connectable channel failed to connect")
        log.debug("Connection established")
        completeConnect(commander, options)
      } catch {
        case e: IOException ⇒ handleError(commander, e)
      }
  }

}
