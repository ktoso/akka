/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.ActorMaterializer
import akka.stream.io.JsonCollectingBuffer.JsonObjectTooLargeException
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

class JsonFramingSpec extends AkkaSpec with ScalaFutures {

  implicit val mat = ActorMaterializer()

  "collecting multiple json" should {
    "parse json array" in {
      val input =
        """
          |[
          | { "name": "john" },
          | { "name": "jack" },
          | { "name": "katie" }
          |]
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john" }""".stripMargin,
        """{ "name": "jack" }""".stripMargin,
        """{ "name": "katie" }""".stripMargin)
    }

    "parse line delimited" in {
      val input =
        """
          | { "name": "john" }
          | { "name": "jack" }
          | { "name": "katie" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john" }""".stripMargin,
        """{ "name": "jack" }""".stripMargin,
        """{ "name": "katie" }""".stripMargin)
    }

    "parse comma delimited" in {
      val input =
        """
          | { "name": "john" }, { "name": "jack" }, { "name": "katie" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john" }""".stripMargin,
        """{ "name": "jack" }""",
        """{ "name": "katie" }""")
    }

    "parse chunks successfully" in {
      val input: Seq[ByteString] = Seq(
        """
          |[
          |  { "name": "john"""".stripMargin,
        """
          |},
        """.stripMargin,
        """{ "na""",
        """me": "jack""",
        """"}]"""").map(ByteString(_))

      val result = Source.apply(input)
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john"
          |}""".stripMargin,
        """{ "name": "jack"}""")
    }
  }

  // TODO fold these specs into the previous section
  "collecting json buffer" when {
    "nothing is supplied" should {
      "return nothing" in {
        val buffer = new JsonCollectingBuffer()
        buffer.poll() shouldBe Success(None)
      }
    }

    "valid json is supplied" which {
      "has one object" should {
        "successfully parse empty object" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{}"""))
          buffer.poll().get.get.utf8String shouldBe """{}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john"}"""))
          buffer.poll().get.get.utf8String shouldBe """{ "name": "john"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value containing space" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john doe"}"""))
          buffer.poll().get.get.utf8String shouldBe """{ "name": "john doe"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value containing curly brace" in {
          val buffer = new JsonCollectingBuffer()

          buffer.append(ByteString("""{ "name": "john{"""))
          buffer.append(ByteString("}"))
          buffer.append(ByteString("\""))
          buffer.append(ByteString("}"))

          buffer.poll().get.get.utf8String shouldBe """{ "name": "john{}"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value containing curly brace and escape character" in {
          val buffer = new JsonCollectingBuffer()

          buffer.append(ByteString("""{ "name": "john"""))
          buffer.append(ByteString("\\\""))
          buffer.append(ByteString("{"))
          buffer.append(ByteString("}"))
          buffer.append(ByteString("\\\""))
          buffer.append(ByteString(" "))
          buffer.append(ByteString("hey"))
          buffer.append(ByteString("\""))

          buffer.append(ByteString("}"))
          buffer.poll().get.get.utf8String shouldBe """{ "name": "john\"{}\" hey"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having integer value" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{ "age": 101}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having decimal value" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{ "age": 101}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having nested object" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(
            """
              |{  "name": "john",
              |   "age": 101,
              |   "address": {
              |     "street": "Straight Street",
              |     "postcode": 1234
              |   }
              |}
              | """.stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{  "name": "john",
                                                      |   "age": 101,
                                                      |   "address": {
                                                      |     "street": "Straight Street",
                                                      |     "postcode": 1234
                                                      |   }
                                                      |}""".stripMargin
          buffer.valid shouldBe true
        }

        "successfully parse single field having multiple level of nested object" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(
            """
              |{  "name": "john",
              |   "age": 101,
              |   "address": {
              |     "street": {
              |       "name": "Straight",
              |       "type": "Avenue"
              |     },
              |     "postcode": 1234
              |   }
              |}
              | """.stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{  "name": "john",
                                                      |   "age": 101,
                                                      |   "address": {
                                                      |     "street": {
                                                      |       "name": "Straight",
                                                      |       "type": "Avenue"
                                                      |     },
                                                      |     "postcode": 1234
                                                      |   }
                                                      |}""".stripMargin
          buffer.valid shouldBe true
        }
      }

      "has nested array" should {
        "successfully parse" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(
            """
              |{  "name": "john",
              |   "things": [
              |     1,
              |     "hey",
              |     3,
              |     "there"
              |   ]
              |}
              | """.stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{  "name": "john",
                                                      |   "things": [
                                                      |     1,
                                                      |     "hey",
                                                      |     3,
                                                      |     "there"
                                                      |   ]
                                                      |}""".stripMargin
          buffer.valid shouldBe true
        }
      }

      "has complex object graph" should {
        "successfully parse" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(
            """
              |{
              |  "name": "john",
              |  "addresses": [
              |    {
              |      "street": "3 Hopson Street",
              |      "postcode": "ABC-123",
              |      "tags": ["work", "office"],
              |      "contactTime": [
              |        {"time": "0900-1800", "timezone", "UTC"}
              |      ]
              |    },
              |    {
              |      "street": "12 Adielie Road",
              |      "postcode": "ZZY-888",
              |      "tags": ["home"],
              |      "contactTime": [
              |        {"time": "0800-0830", "timezone", "UTC"},
              |        {"time": "1800-2000", "timezone", "UTC"}
              |      ]
              |    }
              |  ]
              |}
              | """.stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{
                                                      |  "name": "john",
                                                      |  "addresses": [
                                                      |    {
                                                      |      "street": "3 Hopson Street",
                                                      |      "postcode": "ABC-123",
                                                      |      "tags": ["work", "office"],
                                                      |      "contactTime": [
                                                      |        {"time": "0900-1800", "timezone", "UTC"}
                                                      |      ]
                                                      |    },
                                                      |    {
                                                      |      "street": "12 Adielie Road",
                                                      |      "postcode": "ZZY-888",
                                                      |      "tags": ["home"],
                                                      |      "contactTime": [
                                                      |        {"time": "0800-0830", "timezone", "UTC"},
                                                      |        {"time": "1800-2000", "timezone", "UTC"}
                                                      |      ]
                                                      |    }
                                                      |  ]
                                                      |}""".stripMargin
          buffer.valid shouldBe true
        }
      }

      "has multiple fields" should {
        "parse successfully" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john", "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{ "name": "john", "age": 101}"""
          buffer.valid shouldBe true
        }

        "parse successfully despite valid whitespaces around json" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(
            """
              |
              |
              |{"name":   "john"
              |, "age": 101}""".stripMargin))
          buffer.poll().get.get.utf8String shouldBe
            """{"name":   "john"
              |, "age": 101}""".stripMargin
          buffer.valid shouldBe true
        }
      }

      "has multiple objects" should {
        "pops the right object as buffer is filled" in {
          val input =
            """
              |[
              |  {
              |    "name": "john",
              |    "age": 32
              |  },
              |  {
              |    "name": "katie",
              |    "age": 25
              |  }
              |]
            """.stripMargin

          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString(input))

          buffer.poll().get.get.utf8String shouldBe
            """{
              |    "name": "john",
              |    "age": 32
              |  }""".stripMargin
          buffer.poll().get.get.utf8String shouldBe
            """{
              |    "name": "katie",
              |    "age": 25
              |  }""".stripMargin
          buffer.poll().get shouldBe None

          buffer.append(ByteString("""{"name":"jenkins","age": 65"""))
          buffer.poll().get shouldBe None

          buffer.append(ByteString("}"))
          buffer.poll().get.get.utf8String shouldBe """{"name":"jenkins","age": 65}"""
        }
      }

      "returns none until valid json is encountered" in {
        val buffer = new JsonCollectingBuffer()

        """{ "name": "john"""".stripMargin.foreach {
          c ⇒
            buffer.append(ByteString(c))
            buffer.poll().get shouldBe None
        }

        buffer.append(ByteString("}"))
        buffer.poll().get.get.utf8String shouldBe """{ "name": "john"}"""
        buffer.valid shouldBe true
      }

      "invalid json is supplied" should {
        "fail if it's broken from the start" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""THIS IS NOT VALID { "name": "john"}"""))
          buffer.poll().isFailure shouldBe true
          buffer.valid shouldBe false
        }

        "fail if it's broken at the end" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john"} THIS IS NOT VALID"""))
          buffer.poll().isFailure shouldBe true
          buffer.valid shouldBe false
        }
      }
    }

    "fail on too large initial object" in {
      val input =
        """
          | { "name": "john" }, { "name": "jack" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(5))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      a[JsonObjectTooLargeException] shouldBe thrownBy {
        Await.result(result, 3.seconds)
      }
    }

    "fail when 2nd object is too large" in {
      val input = List(
        """{ "name": "john" }""",
        """{ "name": "jack" }""",
        """{ "name": "very very long name somehow. how did this happen?" }""").map(s ⇒ ByteString(s))

      val probe = Source(input)
        .via(Framing.json(48))
        .runWith(TestSink.probe)

      probe.ensureSubscription()
      probe
        .request(1)
        .expectNext(ByteString("""{ "name": "john" }""")) // FIXME we should not impact the given json in Framing
        .request(1)
        .expectNext(ByteString("""{ "name": "jack" }"""))
        .request(1)
        .expectError().getMessage should include("exceeded")
    }
  }
}