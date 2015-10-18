/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString
import org.scalatest.{Matchers, WordSpec}

import scala.util.Success

class JsonFramingSpec extends WordSpec with Matchers {
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
          buffer.poll().get.get.utf8String shouldBe """{"name":"john"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value containing space" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john doe"}"""))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john doe"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having string value containing curly brace" in {
          val buffer = new JsonCollectingBuffer()

          buffer.append(ByteString("""{ "name": "john{"""))
          buffer.append(ByteString("}"))
          buffer.append(ByteString("\""))
          buffer.append(ByteString("}"))

          buffer.poll().get.get.utf8String shouldBe """{"name":"john{}"}"""
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
          buffer.poll().get.get.utf8String shouldBe """{"name":"john\"{}\" hey"}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having integer value" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{"age":101}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having decimal value" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{"age":101}"""
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
              |""".stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","age":101,"address":{"street":"Straight Street","postcode":1234}}"""
          buffer.valid shouldBe true
        }

        "successfully parse single field having multiple level of nested object" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""
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
                                     |""".stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","age":101,"address":{"street":{"name":"Straight","type":"Avenue"},"postcode":1234}}"""
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
              |""".stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","things":[1,"hey",3,"there"]}"""
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
              |""".stripMargin))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","addresses":[{"street":"3 Hopson Street","postcode":"ABC-123","tags":["work","office"],"contactTime":[{"time":"0900-1800","timezone","UTC"}]},{"street":"12 Adielie Road","postcode":"ZZY-888","tags":["home"],"contactTime":[{"time":"0800-0830","timezone","UTC"},{"time":"1800-2000","timezone","UTC"}]}]}"""
          buffer.valid shouldBe true
        }
      }

      "has multiple fields" should {
        "parse successfully" in {
          val buffer = new JsonCollectingBuffer()
          buffer.append(ByteString("""{ "name": "john", "age": 101}"""))
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","age":101}"""
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
          buffer.poll().get.get.utf8String shouldBe """{"name":"john","age":101}"""
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

          buffer.poll().get.get.utf8String shouldBe """{"name":"john","age":32}"""
          buffer.poll().get.get.utf8String shouldBe """{"name":"katie","age":25}"""
          buffer.poll().get shouldBe None

          buffer.append(ByteString("""{"name":"jenkins","age": 65"""))
          buffer.poll().get shouldBe None

          buffer.append(ByteString("}"))
          buffer.poll().get.get.utf8String shouldBe """{"name":"jenkins","age":65}"""
        }
      }

      "returns none until valid json is encountered" in {
        val buffer = new JsonCollectingBuffer()

        """{ "name": "john"""".stripMargin.foreach { c ⇒
          buffer.append(ByteString(c))
          buffer.poll().get shouldBe None
        }

        buffer.append(ByteString("}"))
        buffer.poll().get.get.utf8String shouldBe """{"name":"john"}"""
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
  }
}