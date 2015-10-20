/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.stream.io.JsonCollectingBuffer
import akka.util.ByteString
import org.openjdk.jmh.annotations._

import scala.util.Try

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class JsonFramingBenchmark {

  val json =
    ByteString("""|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false},
       |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
       |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}""".stripMargin)

  val buf = new JsonCollectingBuffer

  @Benchmark
  def split: Try[Option[ByteString]] = {
    buf.append(json)
    buf.poll()
  }
}
