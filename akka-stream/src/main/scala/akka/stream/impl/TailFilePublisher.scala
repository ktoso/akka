/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.nio.ByteBuffer
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.WatchEvent.Kind
import java.nio.file.{ Path, StandardOpenOption, StandardWatchEventKinds, WatchEvent, WatchKey }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import java.util.concurrent.{ ArrayBlockingQueue, BlockingQueue, ExecutorService, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit }

import akka.actor.{ ActorLogging, Props }
import akka.dispatch.{ MonitorableThreadFactory, ThreadPoolConfig }
import akka.io.DirectByteBufferPool
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.impl.TailFilePublisher.{ SamplingSettings, Stop }
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.forkjoin.ForkJoinPool

private[akka] object TailFilePublisher {

  def props(p: Path, sampling: SamplingSettings, chunkSize: Int, readAhead: Int) = {
    require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")
    require(isPowerOfTwo(chunkSize), s"chunkSize must be power of 2 (was $chunkSize)")
    require(readAhead > 0, s"readAhead must be > 0 (was $readAhead)")

    Props(classOf[TailFilePublisher], p, sampling, chunkSize, readAhead)
  }

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

  /**
   * PRIVATE API
   * For public API use the materialized cancellable of this Source
   */
  private[akka] final case object Stop

  private def isPowerOfTwo(n: Integer): Boolean = (n & (n - 1)) == 0 // FIXME this considers 0 a power of 2
}

/** JDK7+ ONLY */
private[akka] class TailFilePublisher(
  path: Path, sampling: SamplingSettings, chunkLength: Int, poolEntries: Int)
  extends akka.stream.actor.ActorPublisher[ByteString] with ActorLogging {

  private[this] val buffs = new DirectByteBufferPool(chunkLength, poolEntries)

  private[this] val fileOffset = new AtomicLong(0)

  private[this] val readsInFlight = new AtomicInteger(0)

  private val config = context.system.settings.config.getConfig("akka.stream.file-io.executor")
  private[this] val fileIoExecutor = new ThreadPoolConfigurator(config).pool
  private[this] val chan = AsynchronousFileChannel.open(path, Set(StandardOpenOption.READ).asJava, fileIoExecutor)

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
  final case class ChunkOffer(fromFileOffset: Long, bs: ByteString)
  final case class ReadFailure(fromFileOffset: Long, thr: Throwable)

  implicit val offerOrdering = new Ordering[ChunkOffer] {
    override def compare(x: ChunkOffer, y: ChunkOffer): Int = x.fromFileOffset.compareTo(y.fromFileOffset)
  }
  private var chunkOffers: SortedSet[ChunkOffer] = SortedSet.empty

  /**
   * Used to "debounce" multiple write events coming in during one sampling interval.
   * We emit less yet accumulated ByteStrings instead of N very small ones for such events.
   */
  final case object SamplingTick

  def receive = {
    case Request(n) ⇒
      log.warning("incoming demand: {}, totalDemand: {}", n, totalDemand)
      signalReadyChunks()
      loadAndSignal()

    case offer @ ChunkOffer(offset, bs) ⇒
      // TODO ordering issues, use filePos
      if (totalDemand > 0) onNext(bs)
      else chunkOffers += offer

    case SamplingTick ⇒
      val key = watchService.poll()
      handlePoll(key)

    case Continue ⇒
      loadAndSignal()

    case Stop ⇒
      onComplete()
      context stop self
  }

  def signalReadyChunks(): Unit = {
    var signalled = 0
    val it = chunkOffers.iterator
    while (it.hasNext && totalDemand > 0) {
      val offer = it.next()
      log.warning("Signalling chunk from offset {}, size {}", offer.fromFileOffset, offer.bs.size, offer.bs.utf8String)

      onNext(offer.bs)
      signalled += 1
    }

    chunkOffers = chunkOffers.drop(signalled)
  }

  def handlePoll(key: WatchKey): Unit = {
    if (key ne null) {
      val evts = key.pollEvents()
      var i = 0
      val size = evts.size()

      while (i < size) {
        val e = evts.get(i)
        log.warning("Got {}, count {}, kind: {}", e, e.count(), e.kind())

        import java.nio.file.StandardWatchEventKinds._
        // TODO only react on the right event (check context())
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

      if (readsInFlight.get() == 0) {
        log.warning("Initial read...")
        asyncLoadChunk()
      }
    }

  def asyncLoadChunk(): Unit = {
    log.warning("Async load (in flight already: {})", readsInFlight.get())
    val buf = aquire()
    buf.clear()

    val from = fileOffset.get() // TODO ??? because optimistic read-ahead this from must be incremented if we're peeking ahead
    //    val from = fileOffset.get() + (chunkLength * readsInFlight.get)
    log.warning("Loading chunk (fileOffset: {})...", from)

    if (from <= chan.size())
      chan.read(buf, from, buf, new CompletionHandler[Integer, ByteBuffer] {
        override def completed(readBytes: Integer, bytes: ByteBuffer): Unit = {
          if (readBytes > 0) {
            fileOffset.addAndGet(readBytes.toInt)
            log.warning("Read bytes: {}, offset now: {}", readBytes, fileOffset.get)

            bytes.flip()
            val offer = ChunkOffer(from, ByteString(bytes).take(readBytes))
            log.warning("GOT: " + offer.bs.utf8String)

            release(bytes)
            asyncLoadChunk()

            self ! offer
          } else {
            release(bytes)
          }

        }

        override def failed(thr: Throwable, bytes: ByteBuffer): Unit = {
          bytes.flip()
          release(bytes)

          self ! ReadFailure(from, thr)

        }
      })
  }

  def aquire(): ByteBuffer = {
    readsInFlight.incrementAndGet()
    buffs.acquire()
  }

  def release(bytes: ByteBuffer): Unit = {
    readsInFlight.decrementAndGet()
    buffs.release(bytes)
  }

  private def registerWatchService(p: Path, eventKinds: Array[WatchEvent.Kind[_]]): WatchKey = {
    val dir = if (p.toFile.isDirectory) p.toAbsolutePath else p.getParent.toAbsolutePath
    log.warning("Watching {}", dir)
    dir.register(watchService, eventKinds, sampling.sensitivity.underlying)
  }

  override def postStop(): Unit = {
    super.postStop()
    // TODO make sure all are released...?
    watchService.close()
    samplingCancellable.cancel()
  }
}

/**
 * https://raw.githubusercontent.com/drexin/akka-io-file/master/src/main/scala/akka/io/ThreadPoolConfigurator.scala
 */
class ThreadPoolConfigurator(config: Config) {
  val pool: ExecutorService = config.getString("type") match {
    case "fork-join-executor"   ⇒ createForkJoinExecutor(config.getConfig("fork-join-executor"))
    case "thread-pool-executor" ⇒ createThreadPoolExecutor(config.getConfig("thread-pool-executor"))
  }

  private def createForkJoinExecutor(config: Config) =
    new ForkJoinPool(
      ThreadPoolConfig.scaledPoolSize(
        config.getInt("parallelism-min"),
        config.getDouble("parallelism-factor"),
        config.getInt("parallelism-max")),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      MonitorableThreadFactory.doNothing, true)

  private def createThreadPoolExecutor(config: Config) = {
    def createQueue(tpe: String, size: Int): BlockingQueue[Runnable] = tpe match {
      case "array"       ⇒ new ArrayBlockingQueue[Runnable](size, false)
      case "" | "linked" ⇒ new LinkedBlockingQueue[Runnable](size)
      case x             ⇒ throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!" format x)
    }

    val corePoolSize = ThreadPoolConfig.scaledPoolSize(config.getInt("core-pool-size-min"), config.getDouble("core-pool-size-factor"), config.getInt("core-pool-size-max"))
    val maxPoolSize = ThreadPoolConfig.scaledPoolSize(config.getInt("max-pool-size-min"), config.getDouble("max-pool-size-factor"), config.getInt("max-pool-size-max"))

    new ThreadPoolExecutor(
      corePoolSize,
      maxPoolSize,
      config.getDuration("keep-alive-time", TimeUnit.MILLISECONDS),
      TimeUnit.MILLISECONDS,
      createQueue(config.getString("task-queue-type"), config.getInt("task-queue-size")))
  }
}