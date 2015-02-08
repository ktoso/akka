/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import java.io.{FileWriter, BufferedWriter, File}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Lock
import scala.util.Success

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class StreamFileBenchmark {

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
  
  @Param(Array("1024", "1048576"))
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

  @GenerateMicroBenchmark
  def getLines(): Int = {
    val source = scala.io.Source.fromFile(f)
    val lines = source.getLines()
    val i = lines.map(_.length).sum
    source.close()
    i
  }
  
  @GenerateMicroBenchmark
  def getLinesSource(): Int = {
    val source = scala.io.Source.fromFile(f)
    val m = Source(() ⇒ source.getLines()).runWith(sumFoldStringSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    try abq.take() finally source.close()
  }
  
  @GenerateMicroBenchmark
  def blockingIO_mat_fold(): Int = {
    val m = Source(f).runWith(sumFoldSink)(mat)
    m.map { i ⇒ abq.offer(i) }
    
    abq.take()
  }

}
