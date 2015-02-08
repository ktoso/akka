/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.{ File, FileWriter }
import java.util.Random

import akka.stream.FlowMaterializer
import akka.stream.impl.TailFilePublisher
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.util.ByteString

import scala.annotation.tailrec
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

    "Java 7+ only:" must {
      import concurrent.duration._
      val settings = TailFilePublisher.SamplingSettings(100.millis, TailFilePublisher.HighSamplingSensitivity)

      requiresJdk7("tail a file for changes") {
        val f = File.createTempFile("file-source-tailing-spec", ".tmp")

        val tailSource = Source.tail(f.toPath, settings, chunkSize = 128, readAhead = 4)
        val fileLineByLine = tailSource.transform(() ⇒ parseLines("\n", 512))

        fileLineByLine.runWith(Sink.foreach { testActor ! _ })

        val writer = new FileWriter(f)
        val line = "Whoa, mathematical!"
        def writeLines(n: Int) = (1 to n) foreach { i ⇒ writer.append(line + "\n").flush() }

        try {

          expectNoMsg(300.millis)

          // collapse multiple writes into one bytestring
          writeLines(3)
          within(10.seconds) {
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
          }

          // single writes can be read one by one if only one write during interval
          writeLines(1)
          within(10.seconds) {
            expectMsgType[String] should ===(line)
            writeLines(1)
            expectMsgType[String] should ===(line)
          }

          // large amount of writes should be split up in batchSized byteStrings
          writeLines(10)
          within(5.seconds) {
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
            expectMsgType[String] should ===(line)
          }

        } finally
          try f.delete() finally writer.close()
      }

      // TODO test for ordering issues
    }

    def requiresJdk7(s: String)(block: ⇒ Unit) = {
      val jv = System.getProperty("java.version")
      if (jv.startsWith("1.6")) s in pending
      else s"[JDK: $jv]: $s" in block
    }
  }

  final case class Settings(chunkSize: Int, readAhead: Int)

  import akka.stream.stage._

  def parseLines(separator: String, maximumLineBytes: Int) =
    new StatefulStage[ByteString, String] {
      private val separatorBytes = ByteString(separator)
      private val firstSeparatorByte = separatorBytes.head
      private var buffer = ByteString.empty
      private var nextPossibleMatch = 0

      def initial = new State {
        override def onPush(chunk: ByteString, ctx: Context[String]): Directive = {
          buffer ++= chunk
          if (buffer.size > maximumLineBytes)
            ctx.fail(new IllegalStateException(s"Read ${buffer.size} bytes " +
              s"which is more than $maximumLineBytes without seeing a line terminator"))
          else emit(doParse(Vector.empty).iterator, ctx)
        }

        @tailrec
        private def doParse(parsedLinesSoFar: Vector[String]): Vector[String] = {
          val possibleMatchPos = buffer.indexOf(firstSeparatorByte, from = nextPossibleMatch)
          if (possibleMatchPos == -1) {
            // No matching character, we need to accumulate more bytes into the buffer
            nextPossibleMatch = buffer.size
            parsedLinesSoFar
          } else {
            if (possibleMatchPos + separatorBytes.size > buffer.size) {
              // We have found a possible match (we found the first character of the terminator
              // sequence) but we don't have yet enough bytes. We remember the position to
              // retry from next time.
              nextPossibleMatch = possibleMatchPos
              parsedLinesSoFar
            } else {
              if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size)
                == separatorBytes) {
                // Found a match
                val parsedLine = buffer.slice(0, possibleMatchPos).utf8String
                buffer = buffer.drop(possibleMatchPos + separatorBytes.size)
                nextPossibleMatch -= possibleMatchPos + separatorBytes.size
                doParse(parsedLinesSoFar :+ parsedLine)
              } else {
                nextPossibleMatch += 1
                doParse(parsedLinesSoFar)
              }
            }
          }

        }
      }
    }
}

