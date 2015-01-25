/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.{ File, FileWriter }

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

  override protected def afterTermination(): Unit = testFile.delete()

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

    "count lines in real file" in {
      val s = Source(new File("/Users/ktoso/code/deletions/deletions.csv-00000-of-00020"), chunkSize = 4096, readAhead = 2)
      val f = s.runWith(Sink.fold(0) {
        case (acc, l) ⇒ acc + l.count(_ == '\n')
      })

      import concurrent.duration._
      val lineCount = Await.result(f, 3.hours)
      info("Lines = " + lineCount)
      lineCount should ===(3152500)
    }
  }

}

