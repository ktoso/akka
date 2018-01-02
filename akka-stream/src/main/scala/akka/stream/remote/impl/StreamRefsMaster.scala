/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.impl

import akka.actor.{ Actor, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props }
import akka.stream.impl.SeqActorName
import akka.stream.remote.impl.StreamRefsMasterActor.AllocatePusherToRemoteSink
import akka.stream.remote.scaladsl.SinkRef

object StreamRefsMaster extends ExtensionId[StreamRefsMaster] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): StreamRefsMaster =
    new StreamRefsMaster(system)

  override def lookup(): StreamRefsMaster.type = this

  override def get(system: ActorSystem): StreamRefsMaster = super.get(system)
}

/** INTERNAL API */
private[stream] final class StreamRefsMaster(system: ExtendedActorSystem) extends Extension {

  private[this] val sinkRefTargetSourceNames = SeqActorName("SinkRefTargetSource") // "local target"
  private[this] val sinkRefNames = SeqActorName("SinkRefActor") // "remote sender"

  // TODO do we need it? perhaps for reaping?
  // system.systemActorOf(StreamRefsMasterActor.props(), "streamRefsMaster")

  def nextSinkRefTargetSourceName(): String =
    sinkRefTargetSourceNames.next()

  def nextSinkRefName(): String =
    sinkRefNames.next()

}

object StreamRefsMasterActor {
  def props(): Props = Props(new StreamRefsMasterActor())

  final case class AllocatePusherToRemoteSink(stageRef: ActorRef)
}

class StreamRefsMasterActor extends Actor {
  override def receive: Receive = {
    case AllocatePusherToRemoteSink(stageRef) ⇒
    //      context.actorOf()
  }
}
