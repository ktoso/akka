/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.io.{ BufferedInputStream, FileInputStream }
import java.nio.ByteBuffer
import java.nio.file.WatchEvent.Kind
import java.nio.file.{ Path, StandardWatchEventKinds, WatchEvent, WatchKey }

import akka.actor.{ ActorLogging, Props }
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.impl.TailFilePublisher.SamplingSettings
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

private[akka] object TailFilePublisher {

  /**
   * TODO docs
   */
  case class SamplingSettings(
    interval: FiniteDuration,
    sensitivity: TailSamplingSensitivity) {
    require(interval.toMillis > 0, s"samplingRate must be > 0ms (was $interval)")
  }

  sealed trait TailSamplingSensitivity { /** PRIVATE API */ private[akka] def underlying: WatchEvent.Modifier }

  case object HighSamplingSensitivity extends TailSamplingSensitivity {
    override def underlying = com.sun.nio.file.SensitivityWatchEventModifier.HIGH
  }
  case object DefaultSamplingSensitivity extends TailSamplingSensitivity {
    override def underlying = com.sun.nio.file.SensitivityWatchEventModifier.MEDIUM
  }
  case object LowSamplingSensitivity extends TailSamplingSensitivity {
    override def underlying = com.sun.nio.file.SensitivityWatchEventModifier.LOW
  }

  def props(p: Path, sampling: SamplingSettings, chunkSize: Int, readAhead: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(isPowerOfTwo(chunkSize), s"chunkSize must be power of 2 (was $chunkSize)")
    require(readAhead > 0, s"readAhead must be > 0 (was $readAhead)")

    Props(classOf[TailFilePublisher], p, sampling, chunkSize, readAhead)
      .withDispatcher("stream-file-io-dispatcher") // TODO: use a dedicated dispatcher for it
  }

  /**
   * PRIVATE API
   * For public API use the materialized cancellable of this Source
   */
  private[akka] final case object Stop

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0 // FIXME this considers 0 a power of 2
}

/** JDK7+ ONLY */
private[akka] class TailFilePublisher(
  path: Path, sampling: SamplingSettings, chunkLength: Int, chunkSlots: Int)
  extends akka.stream.actor.ActorPublisher[ByteString] with ActorLogging {

  private[this] val bufferSize = chunkLength * chunkSlots
  require(bufferSize < Int.MaxValue, "bufferSize (chunkLength * readAhead) must be < Int.MaxValue!")

  private[this] val buf = ByteBuffer.allocate(bufferSize)
  log.warning("Allocated buffer: {}", buf.capacity())

  private[this] var writePos = 0
  private[this] var readPos = 0
  private[this] var readCompletedAt = Int.MaxValue
  private[this] var availableChunks = 0

  private[this] val chunkSizes: Array[Int] = Array.ofDim(chunkSlots)

  //  private[this] val chunkLenght: AtomicReference[Array[Int]] = new AtomicReference(Array(0 until readAhead): _*)

  private[this] lazy val stream = new BufferedInputStream(new FileInputStream(path.toFile))

  //  private[this] val chan = AsynchronousFileChannel.open(path, StandardOpenOption.READ)

  private[this] val watchService = path.getFileSystem.newWatchService()

  private[this] val eventKinds: Array[Kind[_]] = Array(
    StandardWatchEventKinds.ENTRY_CREATE,
    StandardWatchEventKinds.ENTRY_MODIFY,
    StandardWatchEventKinds.ENTRY_DELETE,
    StandardWatchEventKinds.OVERFLOW)

  val watckedKey = registerWatchService(path, eventKinds)

  implicit val ec = context.system.dispatcher
  private val samplingCancellable = context.system.scheduler.schedule(sampling.interval, sampling.interval, self, SamplingTick)

  final case object Continue

  /**
   * Used to "debounce" multiple write events coming in during one sampling interval.
   * We emit less yet accumulated ByteStrings instead of N very small ones for such events.
   */
  final case object SamplingTick

  def receive = {
    case Request(n) ⇒
      log.warning("incoming demand: {}, totalDemand: {}", n, totalDemand)
      // okey, we know
      loadAndSignal()

    case SamplingTick ⇒
      val key = watchService.poll()
      handlePoll(key)

    case Continue ⇒
      loadAndSignal()
  }

  def handlePoll(key: WatchKey): Unit = {
    if (key ne null)
      log.warning("key = {}", key)

    if (key ne null) {
      val evts = key.pollEvents()
      var i = 0
      val size = evts.size()

      while (i < size) {
        val e = evts.get(i)
        log.warning("Got {}, count {}, kind: {}", e, e.count(), e.kind())

        //        if (e.context().asInstanceOf[Path].getFileName == path.getFileName) { // TODO fix this
        import java.nio.file.StandardWatchEventKinds._
        e.kind() match {
          case OVERFLOW | ENTRY_MODIFY ⇒
            loadAndSignal()
            self ! Continue
        }

        i += 1
      }

      if (!key.reset()) onComplete()
    }
  }

  def loadAndSignal(): Unit =
    if (isActive) {
      log.warning("load and signal...")

      // signal from available buffer right away
      signalOnNexts()

      // read chunks
      var keepReading = true
      while (availableChunks < chunkSlots && keepReading)
        keepReading = loadChunk()

      // keep signalling
      if (keepReading && totalDemand > 0) self ! Continue

    }

  @tailrec final def signalOnNexts(): Unit =
    if (availableChunks > 0) {
      val offset = chunkOffset(readPos)
      val take = takeResetChunkSize(readPos)
      val bytes = ByteString(buf.array).drop(offset).take(take)
      availableChunks -= 1
      readPos = if (readPos + 1 >= chunkSlots) 0 else readPos + 1

      onNext(bytes)
      log.warning("Signalled chunk: [{}] (offset: {}), chunks still available: {}, demand: {}", bytes.utf8String, offset, availableChunks, totalDemand)

      log.warning(s"total demand = $totalDemand")
      if (offset >= readCompletedAt) {
        // end-of-file reached
        //        log.warning("Signalling last chunk [{}], completing now...", bytes.utf8String)
        onComplete()
      } else if (totalDemand > 0) {
        log.warning(s"More to signal, total demand = $totalDemand, available = $availableChunks")
        signalOnNexts()
      }
    } else if (eofEncountered) onComplete()

  /** BLOCKING I/O READ */
  def loadChunk(): Boolean = {
    log.warning("Loading chunk, into writePos {} ({} * {})...", writePos, writePos, chunkLength)

    val writeOffset = writePos * chunkLength

    // blocking read
    val readBytes = stream.read(buf.array, writeOffset, chunkLength)

    readBytes match {
      case -1 ⇒
        // ignore "EOF" as we're tailing it, contents may come soon after
        // nothing to read, stop reading
        log.warning("Stop reading, EOF encountered, writePos = {}", writePos)
        chunkSizes(writePos) = 0
        false

      case _ ⇒
        availableChunks += 1
        chunkSizes(writePos) = readBytes // mark how many bytes read for this chunk, can often be less than chunk size(!)
        writePos = if (writePos + 1 >= chunkSlots) 0 else writePos + 1

        // valid read, continue
        true
    }
  }

  final def chunkOffset(pos: Int): Int = {
    log.warning("pos * chunkSize = {}", pos * chunkLength)
    pos * chunkLength
  }

  final def takeResetChunkSize(pos: Int): Int = {
    val s = chunkSizes(pos)
    chunkSizes(pos) = 0 // marks as "free"
    s
  }

  final def eofEncountered: Boolean = readCompletedAt != Int.MaxValue

  private def registerWatchService(p: Path, eventKinds: Array[WatchEvent.Kind[_]]): WatchKey = {
    val dir = if (p.toFile.isDirectory) p.toAbsolutePath else p.getParent.toAbsolutePath
    log.warning("Watching {}", dir)
    dir.register(watchService, eventKinds, sampling.sensitivity.underlying)
  }

  override def postStop(): Unit = {
    super.postStop()
    try stream.close() finally samplingCancellable.cancel()
    watchService.close()
  }
}

