/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.nio.ByteBuffer

import akka.actor.{ ActorPath, ActorSystem, Address, EmptyLocalActorRef, ExtendedActorSystem, InternalActorRef, MinimalActorRef }
import akka.event.Logging
import akka.remote.artery.Encoder.OutboundCompressionAccess
import akka.remote.artery._
import akka.remote.artery.compress.CompressionTable
import akka.serialization.Serialization
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.util.{ ByteString, OptionVal }

import scala.concurrent.Await
import scala.concurrent.duration._

object ArteryMessageSizeTool extends App {
  implicit val system = ActorSystem()

  @volatile var rendered: EnvelopeBuffer = null

  try apply("Hello") finally system.terminate()

  def apply(msg: AnyRef) = {
    val exSystem = system.asInstanceOf[ExtendedActorSystem]
    implicit val mat = ActorMaterializer()

    val ppool = ReusableOutboundEnvelope.createObjectPool(capacity = 12)
    val pool = new EnvelopeBufferPool(maximumPayload = 1024 * 10, maximumBuffers = 4)
    val t = new ArteryTransport(exSystem, new RemoteActorRefProvider("local-system", system.settings, system.eventStream, exSystem.dynamicAccess))
    t._localAddress = UniqueAddress(Address("akka", "testSystem", "127.0.0.1", 5225), 1212121)

    val uniqueAddress = UniqueAddress(Address("akka", "testSystem", "127.0.0.1", 5225), 1212121)
    val encoder = Flow.fromGraph(new Encoder(uniqueAddress, exSystem, ppool, pool, debugLogSend = true))

    val theSender = new MinimalActorRef {
      override def provider = t.provider
      override def path = system / "user" / "sender"
    }
    val theRecipient = new RemoteActorRef(
      t,
      uniqueAddress.address,
      ActorPath.fromString("akka://lightningOps@10.0.0.1:25520/user/controls/lightbulbs/xs-1337"),
      new EmptyLocalActorRef(t.provider, system / "user" / "parent", system.eventStream),
      None, None
    )

    val ((queue, compressionAccess), envelopeBufferFuture) =
      Source.queue[AnyRef](10, OverflowStrategy.fail)
        .map { msg ⇒
          (new ReusableOutboundEnvelope)
            .init(
              recipient = OptionVal.Some(theRecipient),
              message = msg,
              sender = OptionVal.Some(theSender)
            )
        }
        .viaMat(encoder)(Keep.both)
        .toMat(Sink.foreach(it ⇒ rendered = it))(Keep.both)
        .run()

    // ------------------
    queue.offer(msg)
    Thread.sleep(1000)
    val out1 = ByteString(rendered.byteBuffer)
    println(Console.YELLOW + "------------ UNCOMPRESSED ------------")
    println(printByteString(out1))
    println(Console.RESET)

    compressionAccess.changeActorRefCompression(CompressionTable.empty.copy(
      originUid = 222,
      version = 1,
      dictionary = Map(
        theRecipient → 1,
        theSender → 2
      )
    ))
    Thread.sleep(1000)
    queue.offer(msg)
    Thread.sleep(1000)
    val out2 = ByteString(rendered.byteBuffer)
    println(Console.GREEN + "------------ COMPRESSED ------------")
    println(printByteString(out2))
    println(Console.RESET)

    system.terminate()
  }

  def printByteString(bytes: ByteString): String = {
    val indent = " "

    def formatBytes(bs: ByteString): Iterator[String] = {
      def asHex(b: Byte): String = b formatted "%02X"

      def asASCII(b: Byte): Char =
        if (b >= 0x20 && b < 0x7f) b.toChar
        else '.'

      def formatLine(bs: ByteString): String = {
        val hex = bs.map(asHex).mkString(" ")
        val ascii = bs.map(asASCII).mkString
        f"$indent%s  $hex%-48s | $ascii"
      }

      def formatBytes(bs: ByteString): String =
        bs.grouped(16).map(formatLine).mkString("\n")

      val prefix = s"${indent}ByteString(${bs.size} bytes)"

      Iterator(prefix + "\n", formatBytes(bs))
    }

    formatBytes(bytes).mkString("")
  }

}
