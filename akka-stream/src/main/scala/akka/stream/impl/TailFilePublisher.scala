/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.nio.ByteBuffer
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.WatchEvent.Kind
import java.nio.file.{ StandardOpenOption, Path, StandardWatchEventKinds, WatchEvent, WatchKey }

import akka.actor.{ ActorLogging, Props }
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.impl.TailFilePublisher.SamplingSettings
import akka.util.ByteString

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
  path: Path, sampling: SamplingSettings, chunkSize: Int, readAhead: Int)
  extends akka.stream.actor.ActorPublisher[ByteString] with ActorLogging {

  private[this] val bufferSize = chunkSize * readAhead
  require(bufferSize < Int.MaxValue, "bufferSize (chunkSize * readAhead) must be < Int.MaxValue!")
  private[this] val buf = ByteBuffer.allocate(bufferSize)

  private[this] var filePos = 0
  private[this] var writePos = 0
  private[this] var readPos = -1
  private[this] var lastReadCompletedPos = 0
  private[this] var availableChunks = 0
  private[this] var pendingReads = false

  private[this] val chan = AsynchronousFileChannel.open(path, StandardOpenOption.READ)

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

  final case class Emit(b: ByteString)
  final case class Fail(thr: Throwable)

  /**
   * Used to "debounce" multiple write events coming in during one sampling interval.
   * We emit less yet accumulated ByteStrings instead of N very small ones for such events.
   */
  final case object SamplingTick

  def receive = {
    case Request(n) ⇒
    // okey

    case SamplingTick ⇒
      val key = watchService.poll()
      handlePoll(key)

    case Emit(bs) ⇒
      filePos += bs.size

      if (totalDemand > 0) onNext(bs)
      else ???

    case Continue ⇒ if (totalDemand > 0)
      () // TODO
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
        log.warning("Got {}, count {}, context: {}", e, e.count(), e.context().asInstanceOf[Path].toAbsolutePath)

        if (e.context().asInstanceOf[Path].getFileName == path.getFileName) { // TODO fix this
          import java.nio.file.StandardWatchEventKinds._
          e.kind() match {
            case OVERFLOW | ENTRY_MODIFY ⇒
              log.warning("Modified: {}", e.context())

              if (writePos < readPos) {
                buf.limit(readPos - writePos)
                chan.read(buf, writePos, writePos, CompletionHandler)
              } else if (writePos > readPos) {
                buf.limit(buf.capacity)
                chan.read(buf, writePos, writePos, CompletionHandler)
              } else
                pendingReads = true

          }

        }

        i += 1
      }

      if (!key.reset()) onComplete()
    }
  }

  def pullFromFile(): Unit = {

  }

  def pushBuffer(): Unit =
    while (totalDemand > 0 && availableChunks > 0)
      onNext(takeChunk())

  def takeChunk(): ByteString = {
    val take = math.min(chunkSize, lastReadCompletedPos)
    ByteString(buf).drop(readPos)

  }

  final val CompletionHandler = new CompletionHandler[Integer, Any] {
    override def completed(bytesRead: Integer, attachment: Any): Unit = {
      val s = ByteString(buf.array).drop(writePos).take(bytesRead)
      log.warning("Got bytes: " + bytesRead + ": " + s.utf8String)

      self ! Emit(s)
    }

    override def failed(thr: Throwable, attachment: Any): Unit =
      self ! Fail(thr)
  }

  private def registerWatchService(p: Path, eventKinds: Array[WatchEvent.Kind[_]]): WatchKey = {
    val dir = if (p.toFile.isDirectory) p.toAbsolutePath else p.getParent.toAbsolutePath
    log.warning("Watching {}", dir)
    dir.register(watchService, eventKinds, sampling.sensitivity.underlying)
  }

  final def chunkOffset(pos: Int): Int = pos * chunkSize

  final def eofEncountered: Boolean = lastReadCompletedPos != Int.MaxValue

  override def postStop(): Unit = {
    super.postStop()
    samplingCancellable.cancel()
    watchService.close()
  }
}

