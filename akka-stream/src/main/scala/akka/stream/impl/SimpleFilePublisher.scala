/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.io.{ BufferedInputStream, File, FileInputStream }
import java.nio.ByteBuffer

import akka.actor.{ ActorLogging, Props }
import akka.stream.actor.ActorPublisherMessage
import akka.util.ByteString

import scala.annotation.tailrec

private[akka] object SimpleFilePublisher {
  def props(f: File, chunkSize: Int, readAhead: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(readAhead > 0, s"readAhead must be > 0 (was $readAhead)")
    Props(classOf[SimpleFilePublisher], f, chunkSize, readAhead)
      .withDispatcher("stream-file-io-dispatcher")
    // TODO: use a dedicated dispatcher for it
  }

}

private[akka] class SimpleFilePublisher(f: File, chunkSize: Int, readAhead: Int) extends akka.stream.actor.ActorPublisher[ByteString]
  with ActorLogging {

  private[this] val bufferSize = chunkSize * readAhead
  require(bufferSize < Int.MaxValue, "bufferSize (chunkSize * readAhead) must be < Int.MaxValue!")
  private[this] val buffer = ByteBuffer.allocate(bufferSize)

  private[this] var writePos = 0
  private[this] var readPos = 0
  private[this] var eofReachedAtOffset = Int.MaxValue
  private[this] var availableChunks = 0

  private[this] lazy val stream = new BufferedInputStream(new FileInputStream(f))

  final case object Continue

  def receive = {
    case ActorPublisherMessage.Request(elements) ⇒
      log.info("Demand added " + elements + ", now at " + totalDemand)

      loadAndSignal()

    case Continue ⇒ if (totalDemand > 0) loadAndSignal()
  }

  def loadAndSignal(): Unit =
    if (isActive) {
      log.info("load and signal...")

      // signal from available buffer right away
      signalOnNexts()

      // read chunks
      while (availableChunks < readAhead && !eofEncountered)
        readChunk()

      // keep signalling
      if (totalDemand > 0) self ! Continue

    }

  @tailrec final def signalOnNexts(): Unit =
    if (availableChunks > 0) {
      val offset = chunkOffset(readPos)

      val take = math.min(chunkSize, eofReachedAtOffset)
      val bytes = ByteString(buffer.array).drop(offset).take(take)
      availableChunks -= 1
      readPos = if (readPos + 1 >= readAhead) 0 else readPos + 1

      onNext(bytes)
      //      log.info("Signalled chunk: [{}] (offset: {}), chunks still available: {}, demand: {}", bytes.utf8String, offset, availableChunks, totalDemand)

      log.info(s"total demand = $totalDemand")
      if (offset >= eofReachedAtOffset) {
        // end-of-file reached
        //        log.info("Signalling last chunk [{}], completing now...", bytes.utf8String)
        onComplete()
      } else if (totalDemand > 0) {
        log.info(s"More to signal, total demand = $totalDemand, available = $availableChunks")
        signalOnNexts()
      }
    } else if (eofEncountered) onComplete()

  /** BLOCKING I/O READ */
  def readChunk() = {
    log.info("Loading chunk, into writePos {} ({} * {})...", writePos, writePos, chunkSize)

    val writeOffset = writePos * chunkSize

    // blocking read
    val readBytes = {
      val i = stream.read(buffer.array, writeOffset, chunkSize)
      log.info("Loaded " + i + " bytes")
      i
    }

    readBytes match {
      case -1 ⇒
        // had nothing to read into this chunk, will complete now
        eofReachedAtOffset = (if (writePos == 0) readAhead - 1 else writePos - 1) * chunkSize
        log.info("No more bytes available to read (got `-1` from `read`), marking final bytes of file @ " + eofReachedAtOffset)

      case advanced if advanced < chunkSize ⇒
        // this was the last chunk, be ready to complete once it has been emited
        eofReachedAtOffset = writeOffset + advanced
        log.info(s"Last chunk loaded, end of file marked at offset: $eofReachedAtOffset")

        availableChunks += 1
        writePos = if (writePos + 1 >= readAhead) 0 else writePos + 1
      //        log.info("writePos advanced to {}, chunksAvailable: {}, buf: {}", writePos, availableChunks, ByteString(buffer.array).utf8String)

      case _ ⇒
        availableChunks += 1
        writePos = if (writePos + 1 >= readAhead) 0 else writePos + 1
      //        log.info("writePos advanced to {}, chunksAvailable: {}, buf: {}", writePos, availableChunks, ByteString(buffer.array).utf8String)

      // valid read, continue
    }
  }

  final def chunkOffset(pos: Int): Int = pos * chunkSize

  final def eofEncountered: Boolean = eofReachedAtOffset != Int.MaxValue

  override def postStop(): Unit = {
    super.postStop()
    stream.close()
  }
}

