/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.{ File, FileWriter }
import java.util.Random

import akka.stream.FlowMaterializer
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.util.ByteString

import scala.concurrent.Await

class FileSourceSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  val testFile = {
    val f = File.createTempFile("file-source-spec", "tmp")
    new FileWriter(f)
      .append("a" * 10)
      .append("b" * 10)
      .append("c" * 10)
      .append("d" * 10)
      .append("e" * 10)
      .append("f" * 10)
      .close()
    f
  }

  val LinesCount = 2000 + new Random().nextInt(300)
  val manyLines = {
    info("manyLines.lines = " + LinesCount)

    val f = File.createTempFile(s"file-source-spec-lines_$LinesCount", "tmp")
    val w = new FileWriter(f)
    (1 to LinesCount).foreach { l ⇒
      w.append("a" * l).append("\n")
    }
    w.close()
    f
  }

  override def afterTermination(): Unit = {
    testFile.delete()
    manyLines.delete()
  }

  "File Source" must {
    "read lines with readAhead 2" in {
      val chunkSize = 10
      val readAhead = 2

      val p = Source(testFile, chunkSize, readAhead).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[ByteString]()
      p.subscribe(c)
      val sub = c.expectSubscription()

      sub.request(3)
      c.expectNext().utf8String should ===("a" * 10)
      c.expectNext().utf8String should ===("b" * 10)
      c.expectNext().utf8String should ===("c" * 10)
      sub.request(1)
      c.expectNext().utf8String should ===("d" * 10)
      sub.request(1)
      c.expectNext().utf8String should ===("e" * 10)
      sub.request(100)
      c.expectNext().utf8String should ===("f" * 10)

      c.expectComplete()
    }

    List(
      Settings(chunkSize = 8, readAhead = 10),
      Settings(chunkSize = 128, readAhead = 1),
      Settings(chunkSize = 256, readAhead = 1),
      Settings(chunkSize = 256, readAhead = 2),
      Settings(chunkSize = 512, readAhead = 1),
      Settings(chunkSize = 512, readAhead = 2),
      Settings(chunkSize = 4096, readAhead = 1)) foreach { settings ⇒
        import settings._

        s"count lines in real file (chunkSize = $chunkSize, readAhead = $readAhead)" in {
          val s = Source(manyLines, chunkSize = 4096, readAhead = 2)
          val f = s.runWith(Sink.fold(0) {
            case (acc, l) ⇒ acc + l.utf8String.count(_ == '\n')
          })

          import concurrent.duration._
          val lineCount = Await.result(f, 3.seconds)
          lineCount should ===(LinesCount)
        }
      }

    "tail a file" in pending
  }

  final case class Settings(chunkSize: Int, readAhead: Int)
}

