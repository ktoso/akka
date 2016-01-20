/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.impl.util._
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

@Fork(1)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class BodyPartParserBenchmark {

  implicit val system = ActorSystem("HttpBenchmark")
  implicit val materializer = ActorMaterializer()

  @Param(Array("immutable.Queue"))
  var it = ""

  /*
[info] Benchmark                                                 (it)       Mode  Cnt      Score      Error  Units
[info] BodyPartParserBenchmark.large                       ArrayDeque       thrpt   20   1059.961 ±  270.834  ops/s
[info] BodyPartParserBenchmark.two                         ArrayDeque       thrpt   20  15412.878 ± 3080.270  ops/s
[info] BodyPartParserBenchmark.withoutEntityMissingHeader  ArrayDeque       thrpt   20  20224.935 ± 2357.290  ops/s
[info] BodyPartParserBenchmark.large                       immutable.Queue  thrpt   20   1777.642 ±  144.613  ops/s
[info] BodyPartParserBenchmark.two                         immutable.Queue  thrpt   20  28597.521 ± 2014.324  ops/s
[info] BodyPartParserBenchmark.withoutEntityMissingHeader  immutable.Queue  thrpt   20  25808.112 ± 2702.026  ops/s
   */

  @TearDown
  def shutdown(): Unit = {
    Await.ready(system.terminate(), 3.seconds)
  }

  @Benchmark
  def two() = Await.ready({
    import system.dispatcher
    Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC" withCharset `UTF-8`,
      """--XYZABC
        |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General]
  }, 1.second)

  @Benchmark
  def withoutEntityMissingHeader() = Await.ready({
    import system.dispatcher
    Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC" withCharset `UTF-8`,
      """--XYZABC
        |Content-type: text/xml
        |Age: 12
        |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[Multipart.General]
  }, 1.second)

  @Benchmark
  def large() = Await.ready({
    import system.dispatcher
    Unmarshal {
      HttpEntity.Default(
        contentType = `multipart/form-data` withBoundary "XYZABC" withCharset `UTF-8`,
        contentLength = 1, // not verified during unmarshalling
        data = Source {
          List(
            ByteString {
              """--XYZABC
                |Content-Disposition: form-data; name="email"
                |
                |test@there.com
                |--XYZABC
                |Content-Dispo""".stripMarginWithNewline("\r\n")
            },
            ByteString {
              """sition: form-data; name="userfile"; filename="test€.dat"
                |Content-Type: application/pdf
                |Content-Transfer-Encoding: binary
                |Content-Additional-1: anything
                |Content-Additional-2: really-anything
                |
                |filecontent
                |--XYZABC--""".stripMarginWithNewline("\r\n")
            })
        })
    }.to[Multipart.FormData].flatMap(_.toStrict(1.second))

  }, 1.second)
}
