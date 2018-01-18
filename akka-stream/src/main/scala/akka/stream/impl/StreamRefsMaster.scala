/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.stream.StreamRefSettings

/** INTERNAL API */
@InternalApi
private[stream] object StreamRefsMaster extends ExtensionId[StreamRefsMaster] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): StreamRefsMaster =
    new StreamRefsMaster(system)

  override def lookup(): StreamRefsMaster.type = this

  override def get(system: ActorSystem): StreamRefsMaster = super.get(system)
}

/** INTERNAL API */
@InternalApi
private[stream] final class StreamRefsMaster(system: ExtendedActorSystem) extends Extension {

  val settings: StreamRefSettings = StreamRefSettings(system)

  private[this] val sourceRefOriginSinkNames = SeqActorName("SourceRefOriginSink") // "local origin"
  private[this] val sourceRefNames = SeqActorName("SourceRef") // "remote receiver"

  private[this] val sinkRefTargetSourceNames = SeqActorName("SourceRef") // "local target"
  private[this] val sinkRefNames = SeqActorName("SinkRef") // "remote sender"

  // TODO introduce a master with which all stages running the streams register themselves?

  def nextSinkRefTargetSourceName(): String =
    sinkRefTargetSourceNames.next()

  def nextSinkRefName(): String =
    sinkRefNames.next()

  def nextSourceRefOriginSinkName(): String =
    sourceRefOriginSinkNames.next()

  def nextSourceRefName(): String =
    sourceRefNames.next()

}
