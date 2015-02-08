/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import java.io.{BufferedWriter, File, FileInputStream, FileWriter}
import java.nio.ByteBuffer
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class fiStreamFileBenchmark {

  val config = ConfigFactory.parseString(
    """
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        loglevel = "WARNING"
       
       stream {
          file-io {
            executor {
              type = "thread-pool-executor"
            }
          }
       }
      }""".stripMargin).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("file-stream-bench", config)
  implicit val dispatcher = system.dispatcher
  
  var mat: FlowMaterializer = _

  final val abq = new ArrayBlockingQueue[Int](1)

  final val sumFoldSink: FoldSink[Int, ByteString] = Sink.fold(0){ (acc, l) ⇒ acc + l.length }
  final val sumFoldStringSink: FoldSink[Int, String] = Sink.fold(0){ (acc, l) ⇒ acc + l.length }

  var runToFold: RunnableFlow = _
  
  @Param(Array("1048576", "10485760"))
  val fileSize = 0 // bytes
  
  var f: File = _

  @Setup
  def setup() {
    val settings = MaterializerSettings(system)

    f = File.createTempFile("file-benchmark", ".tmp")
    val w = new BufferedWriter(new FileWriter(f))
    while (f.length() < fileSize)
      w.write(fileSize.toString + "\n")
    w.close()
    
    runToFold = Source(f).to(sumFoldSink)
    
    mat = FlowMaterializer(settings)
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

//  @Benchmark
//  def bufferedReader_linesStream_jdk8: Long = {
//    val reader = new BufferedReader(new FileReader(f))
//    val lines = reader.lines()
//    val i = lines.count()
//    reader.close()
//    i
//  }
  
  @Benchmark
  def getLines: Int = {
    val source = scala.io.Source.fromFile(f)
    val lines = source.getLines() // defaultCharBufferSize is 8192
    val i = lines.map(_.length).sum
    source.close()
    i
  }
  
  @Benchmark
  def fileChannel: Long = {
    val Size = 512
    
    val fs = new FileInputStream(f)
    val ch = fs.getChannel
    val bb = ByteBuffer.allocate( 512 * 8 )
    val barray = Array.ofDim[Byte](Size)
    
    var checkSum = 0L
    var nRead = 0
    var nGet = 0
    
    while ({nRead=ch.read( bb ); nRead} != -1 ) {
      if ( nRead != 0 ) {
          bb.position(0)
          bb.limit(nRead)
          while (bb.hasRemaining) {
            nGet = Math.min(bb.remaining(), Size)
            bb.get(barray, 0, nGet)

            var i = 0
            while (i < nGet) {
              checkSum += barray(i)
              i += 1
            }
          }
          bb.clear()
        }
    }
    
    fs.close()
    
    checkSum
  }
  
  @Benchmark
  def getLines_asSource: Int = {
    val source = scala.io.Source.fromFile(f)
    val m = Source(() ⇒ source.getLines()).runWith(sumFoldStringSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    try abq.take() finally source.close()
  }
  
  @Benchmark
  def source_blockingIo_chunk_512_ahead_4: Int = {
    val m = Source(f, chunkSize = 512, readAhead = 4).runWith(sumFoldSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    abq.take()
  }
  
  @Benchmark
  def source_blockingIo_chunk_512_ahead_8: Int = {
    val m = Source(f, chunkSize = 512, readAhead = 8).runWith(sumFoldSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    abq.take()
  }
  
  @Benchmark
  def source_blockingIo_chunk_512_ahead_16: Int = {
    val m = Source(f, chunkSize = 512, readAhead = 16).runWith(sumFoldSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    abq.take()
  }

}
