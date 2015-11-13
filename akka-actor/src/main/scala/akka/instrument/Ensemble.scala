/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ DynamicAccess, ActorSystem, ActorRef }
import akka.event.Logging.{ Warning, Error }
import com.typesafe.config.Config
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3

/**
 * Implementation of RemoteInstrumentation that delegates to multiple instrumentations.
 * Contexts are stored as a sequence, and aligned with instrumentations when retrieved.
 * Efficient implementation using an array and manually inlined loops.
 */
final class Ensemble(dynamicAccess: DynamicAccess, config: Config) extends RemoteInstrumentation {

  // DO NOT MODIFY THIS ARRAY
  private[this] val instrumentations: Array[ActorInstrumentation] = {
    import collection.JavaConverters.collectionAsScalaIterableConverter
    val classes = config.getStringList("akka.instrumentations").asScala
    classes.zipWithIndex.map({
      case (instrumentation, index) ⇒
        create(instrumentation, dynamicAccess, config, new Ensemble.MetadataAccessorFactory(index, classes.size))
    }).toArray
  }

  private[this] val length: Int = instrumentations.length

  private[this] val emptyContexts: Array[AnyRef] = Array.fill(length)(ActorInstrumentation.EmptyContext)

  /**
   * Try creating instrumentations with extra MetadataAccessor.Factory argument,
   * otherwise default to normal actor instrumentation creation.
   */
  private def create(instrumentation: String, dynamicAccess: DynamicAccess, config: Config, metadataAccessorFactory: MetadataAccessor.Factory): ActorInstrumentation = {
    val args = List(classOf[DynamicAccess] -> dynamicAccess, classOf[Config] -> config, classOf[MetadataAccessor.Factory] -> metadataAccessorFactory)
    ({
      dynamicAccess.createInstanceFor[ActorInstrumentation](instrumentation, args)
    } recoverWith {
      case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[ActorInstrumentation](instrumentation, args.tail)
    } recoverWith {
      case _: NoSuchMethodException ⇒ dynamicAccess.createInstanceFor[ActorInstrumentation](instrumentation, args.tail.tail)
    } recover {
      case _: NoSuchMethodException ⇒ ActorInstrumentation.create(instrumentation, dynamicAccess, config)
    }).get
  }

  override def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else instrumentations.collectFirst {
      case t if t.access(instrumentationClass) ne null ⇒ t.access(instrumentationClass)
    }.getOrElse(null)).asInstanceOf[T]

  def findAll[T <: ActorInstrumentation](instrumentationClass: Class[T]): Seq[T] =
    instrumentations flatMap { i ⇒ Option(i.access(instrumentationClass)) }

  def findAll[T <: ActorInstrumentation](implicit tag: ClassTag[T]): Seq[T] =
    findAll(tag.runtimeClass.asInstanceOf[Class[T]])

  override def systemStarted(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).systemStarted(system)
      i += 1
    }
  }

  override def systemShutdown(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).systemShutdown(system)
      i += 1
    }
  }

  override def actorCreated(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorCreated(actorRef)
      i += 1
    }
  }

  override def actorStarted(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorStarted(actorRef)
      i += 1
    }
  }

  override def actorStopped(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorStopped(actorRef)
      i += 1
    }
  }

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    val contexts = Array.ofDim[AnyRef](length)
    var i = 0
    var nonEmpty = false
    while (i < length) {
      val context = instrumentations(i).actorTold(actorRef, message, sender)
      contexts(i) = context
      if (context ne ActorInstrumentation.EmptyContext) nonEmpty = true
      i += 1
    }
    if (nonEmpty) contexts else ActorInstrumentation.EmptyContext
  }

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = {
    val contexts = if (context eq ActorInstrumentation.EmptyContext) emptyContexts else context.asInstanceOf[Array[AnyRef]]
    var i = 0
    while (i < length) {
      instrumentations(i).actorReceived(actorRef, message, sender, contexts(i))
      i += 1
    }
  }

  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorCompleted(actorRef, message, sender)
      i += 1
    }
  }

  override def clearContext(): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).clearContext()
      i += 1
    }
  }

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventUnhandled(actorRef, message, sender)
      i += 1
    }
  }

  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventDeadLetter(actorRef, message, sender)
      i += 1
    }
  }

  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventLogWarning(actorRef, warning)
      i += 1
    }
  }

  override def eventLogError(actorRef: ActorRef, error: Error): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventLogError(actorRef, error)
      i += 1
    }
  }

  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventActorFailure(actorRef, cause)
      i += 1
    }
  }

  override val remoteIdentifier: Int = MurmurHash3.stringHash("Ensemble")

  override def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    val contexts = Array.ofDim[AnyRef](length)
    var i = 0
    var nonEmpty = false
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation ⇒
          val context = instrumentation.remoteActorTold(actorRef, message, sender)
          contexts(i) = context
          if (context ne ActorInstrumentation.EmptyContext) nonEmpty = true
        case _ ⇒ contexts(i) = ActorInstrumentation.EmptyContext
      }
      i += 1
    }
    if (nonEmpty) contexts else ActorInstrumentation.EmptyContext
  }

  override def remoteMessageSent(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: AnyRef): Array[Byte] = {
    val contexts = if (context eq ActorInstrumentation.EmptyContext) emptyContexts else context.asInstanceOf[Array[AnyRef]]
    var serializedContexts = List.empty[(Int, Array[Byte])]
    var i = 0
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation ⇒
          val serializedContext = instrumentation.remoteMessageSent(actorRef, message, sender, size, contexts(i))
          if (serializedContext ne null) serializedContexts = (instrumentation.remoteIdentifier -> serializedContext) :: serializedContexts
        case _ ⇒ // ignore non-remote instrumentation
      }
      i += 1
    }
    joinContexts(serializedContexts)
  }

  override def remoteMessageReceived(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: Array[Byte]): Unit = {
    val contexts = splitContexts(context)
    var i = 0
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation if contexts.contains(instrumentation.remoteIdentifier) ⇒
          instrumentation.remoteMessageReceived(actorRef, message, sender, size, contexts(instrumentation.remoteIdentifier))
        case _ ⇒ // ignore non-remote instrumentation
      }
      i += 1
    }
  }

  private val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  private def joinContexts(contexts: List[(Int, Array[Byte])]): Array[Byte] = {
    @tailrec
    def writeContexts(writer: ByteBuffer, contexts: List[(Int, Array[Byte])]): Unit =
      if (contexts.nonEmpty) {
        val context = contexts.head
        writer.putInt(context._1)
        writer.putInt(context._2.length)
        writer.put(context._2)
        writeContexts(writer, contexts.tail)
      }

    val totalSize = (0 /: contexts) {
      case (size, (id, context)) ⇒ size + context.length + (2 * 4)
    }
    val context = Array.ofDim[Byte](totalSize)
    val writer = ByteBuffer.wrap(context).order(byteOrder)
    writeContexts(writer, contexts)
    context
  }

  private def splitContexts(context: Array[Byte]): Map[Int, Array[Byte]] = {
    @tailrec
    def readContexts(reader: ByteBuffer, map: Map[Int, Array[Byte]]): Map[Int, Array[Byte]] =
      if (reader.hasRemaining) {
        val id = reader.getInt
        val length = reader.getInt
        val context = Array.ofDim[Byte](length)
        reader.get(context)
        readContexts(reader, map.updated(id, context))
      } else map

    readContexts(ByteBuffer.wrap(context).order(byteOrder), Map.empty)
  }

}

private[akka] object Ensemble {

  /**
   * Wrap MetadataAccessors so that the underlying metadata is actually an array.
   * The wrapped MetadataAccessor only accesses its own metadata element.
   */
  class MetadataAccessorFactory(index: Int, size: Int) extends MetadataAccessor.Factory {
    override def create[T](accessor: MetadataAccessor[T]): MetadataAccessor[T] =
      new MultiMetadataAccessor(accessor, index, size)
  }

  /**
   * MetadataAccessor wrapper that accesses and stores at a particular index in an array.
   */
  class MultiMetadataAccessor[T](accessor: MetadataAccessor[T], index: Int, size: Int) extends MetadataAccessor[T] {
    val arrayAccessor = new ArrayMetadataAccessor(accessor, index, size)

    override def attachTo(actorRef: ActorRef): T = {
      val array = arrayAccessor.attachTo(actorRef)
      if (array ne null) extractMetadata(array(index))
      else null.asInstanceOf[T]
    }

    override def extractFrom(actorRef: ActorRef): T = {
      val array = arrayAccessor.extractFrom(actorRef)
      if (array ne null) extractMetadata(array(index))
      else null.asInstanceOf[T]
    }

    override def createMetadata(actorRef: ActorRef, clazz: Class[_]): T =
      accessor.createMetadata(actorRef, clazz)

    override def extractMetadata(metadata: AnyRef): T =
      accessor.extractMetadata(metadata)
  }

  /**
   * Metadata accessor that initializes and extracts from object arrays.
   */
  final class ArrayMetadataAccessor[T](accessor: MetadataAccessor[T], index: Int, size: Int) extends MetadataAccessor[Array[AnyRef]] {
    private def empty: Array[AnyRef] = Array.ofDim[AnyRef](size)

    override def createMetadata(actorRef: ActorRef, clazz: Class[_]): Array[AnyRef] = {
      val extracted: Array[AnyRef] = extractFrom(actorRef)
      val array = if (extracted ne null) extracted else empty
      array.update(index, accessor.createMetadata(actorRef, clazz).asInstanceOf[AnyRef])
      array
    }

    override def extractMetadata(metadata: AnyRef): Array[AnyRef] = metadata match {
      case array: Array[AnyRef] ⇒ array
      case _                    ⇒ null
    }
  }
}
