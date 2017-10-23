/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.remote.ContainerFormats
import akka.serialization.{ BaseSerializer, Serialization, SerializationExtension, SerializerWithStringManifest }
import akka.stream.remote.scaladsl.{ SinkRef, SourceRef }

final class StreamRefSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest
  with BaseSerializer {

  private lazy val serialization = SerializationExtension(system)

  override def manifest(o: AnyRef): String = o match {
    case _: SourceRef[_] ⇒ SourceRefManifest
    case _: SinkRef[_]   ⇒ SinkRefManifest
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case ref: SourceRef[_] ⇒ serializeSourceRef(ref).toByteArray
    case ref: SinkRef[_]   ⇒ serializeSinkRef(ref).toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case SinkRefManifest   ⇒ deserializeSinkRef(bytes)
    case SourceRefManifest ⇒ ???
  }

  private def serializeSinkRef(sink: SinkRef[_]): ContainerFormats.ActorRef = {
    ContainerFormats.ActorRef.newBuilder()
      .setPath(Serialization.serializedActorPath(sink.targetRef))
      .build()
  }
  private def serializeSourceRef(source: SourceRef[_]): ContainerFormats.ActorRef = {
    ContainerFormats.ActorRef.newBuilder() // TODO make special proto for it
      .setPath(Serialization.serializedActorPath(source.sourceDriverRef))
      .build()
  }

  private def deserializeSinkRef(bytes: Array[Byte]) = {
    val protoRef = ContainerFormats.ActorRef.parseFrom(bytes)
    val targetRef = serialization.system.provider.resolveActorRef(protoRef.getPath)
    new SinkRef[Any](targetRef)
  }

  private val SourceRefManifest = "A"
  private val SinkRefManifest = "D"

}
