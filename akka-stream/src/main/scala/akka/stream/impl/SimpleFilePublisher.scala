/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.io.{ File, FileInputStream }
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ ActorLogging, Props }
import akka.io.BufferPool
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

  private[this] val buffs = new ByteBufferPool(chunkSize, readAhead)

  private[this] var eofReachedAtOffset = Int.MaxValue

  var readBytesTotal = 0L
  var availableChunks: Vector[ByteString] = Vector.empty

  private[this] lazy val stream = new FileInputStream(f)
  private[this] lazy val chan = new FileInputStream(f).getChannel

  final case object Continue

  def receive = {
    case ActorPublisherMessage.Request(elements) ⇒
      log.info("Demand added " + elements + ", now at " + totalDemand)

      loadAndSignal()

    case Continue ⇒ if (totalDemand > 0) loadAndSignal()
  }

  def loadAndSignal(): Unit =
    if (isActive) {
      // signal from available buffer right away
      signalOnNexts()

      // read chunks
      while (availableChunks.length < readAhead && !eofEncountered)
        loadChunk()

      // keep signalling
      if (totalDemand > 0) self ! Continue

    }

  @tailrec final def signalOnNexts(): Unit =
    if (availableChunks.nonEmpty) {
      val ready = availableChunks.head
      availableChunks = availableChunks.tail

      onNext(ready)
      //      log.info("Signalled chunk: [{}] (offset: {}), chunks still available: {}, demand: {}", bytes.utf8String, offset, availableChunks, totalDemand)

      if (totalDemand > 0) {
        log.info(s"More to signal, total demand = $totalDemand, available = ${availableChunks.size}")
        signalOnNexts()
      }
    } else if (eofEncountered) onComplete()

  /** BLOCKING I/O READ */
  def loadChunk() = {
    log.info("Loading chunk ({})...", chunkSize)

    val buf = buffs.acquire()
    buf.clear()

    // blocking read
    val readBytes = chan.read(buf)
    log.info(s"Loaded $readBytes bytes")

    readBytes match {
      case -1 ⇒
        // had nothing to read into this chunk, will complete now
        eofReachedAtOffset = -1
        log.info("No more bytes available to read (got `-1` from `read`), marking final bytes of file @ " + eofReachedAtOffset)

      case _ ⇒
        availableChunks :+= ByteString(buf).take(readBytes)
        buffs.release(buf)

      // valid read, continue
    }
  }

  private final def eofEncountered: Boolean = eofReachedAtOffset != Int.MaxValue

  override def postStop(): Unit = {
    super.postStop()
    try chan.close() finally stream.close()
  }
}

// TODO FIX THIS IN AKKA INSTEAD OF COPY PASTE
/**
 * INTERNAL API
 *
 * A buffer pool which keeps a free list of direct buffers of a specified default
 * size in a simple fixed size stack.
 *
 * If the stack is full a buffer offered back is not kept but will be let for
 * being freed by normal garbage collection.
 */
private[akka] class ByteBufferPool(defaultBufferSize: Int, maxPoolEntries: Int) extends BufferPool {
  private[this] val locked = new AtomicBoolean(false)
  private[this] val pool: Array[ByteBuffer] = new Array[ByteBuffer](maxPoolEntries)
  private[this] var buffersInPool: Int = 0

  def acquire(): ByteBuffer =
    takeBufferFromPool()

  def release(buf: ByteBuffer): Unit =
    offerBufferToPool(buf)

  private def allocate(size: Int): ByteBuffer =
    ByteBuffer.allocate(size)

  @tailrec
  private final def takeBufferFromPool(): ByteBuffer =
    if (locked.compareAndSet(false, true)) {
      val buffer =
        try if (buffersInPool > 0) {
          buffersInPool -= 1
          pool(buffersInPool)
        } else null
        finally locked.set(false)

      // allocate new and clear outside the lock
      if (buffer == null)
        allocate(defaultBufferSize)
      else {
        buffer.clear()
        buffer
      }
    } else takeBufferFromPool() // spin while locked

  @tailrec
  private final def offerBufferToPool(buf: ByteBuffer): Unit =
    if (locked.compareAndSet(false, true))
      try if (buffersInPool < maxPoolEntries) {
        pool(buffersInPool) = buf
        buffersInPool += 1
      } // else let the buffer be gc'd
      finally locked.set(false)
    else offerBufferToPool(buf) // spin while locked
}
