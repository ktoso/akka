package akka.http.scaladsl.server

import akka.util.ByteString
import org.scalatest.{ Matchers, FunSpec }

import scala.util.Success

class JsonCollectingBufferSpec extends FunSpec with Matchers {
  describe("collecting json buffer") {
    describe("when nothing is supplied") {
      it("returns nothing") {
        val buffer = new JsonCollectingBuffer()
        buffer.pop shouldBe Success(None)
      }
    }

    describe("when valid json is supplied") {
      describe("one object") {

        describe("empty") {
          it("returns complete json") {
            val buffer = new JsonCollectingBuffer()
            buffer.append(ByteString("""{}"""))
            buffer.pop.get.get.utf8String shouldBe """{}"""
            buffer.valid shouldBe true
          }
        }

        describe("single field") {
          describe("string value") {
            it("returns complete json") {
              val buffer = new JsonCollectingBuffer()
              buffer.append(ByteString("""{ "name": "john"}"""))
              buffer.pop.get.get.utf8String shouldBe """{"name":"john"}"""
              buffer.valid shouldBe true
            }

            describe("with space") {
              it("returns complete json") {
                val buffer = new JsonCollectingBuffer()
                buffer.append(ByteString("""{ "name": "john doe"}"""))
                buffer.pop.get.get.utf8String shouldBe """{"name":"john doe"}"""
                buffer.valid shouldBe true
              }
            }

            describe("curly brace is nested within a string") {
              it("returns complete json") {
                val buffer = new JsonCollectingBuffer()

                buffer.append(ByteString("""{ "name": "john{"""))
                buffer.append(ByteString("}"))
                buffer.append(ByteString("\""))
                buffer.append(ByteString("}"))

                buffer.pop.get.get.utf8String shouldBe """{"name":"john{}"}"""
                buffer.valid shouldBe true
              }
            }

            describe("curly brace is nested within a string with escape character") {
              it("returns complete json") {
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
                buffer.pop.get.get.utf8String shouldBe """{"name":"john\"{}\" hey"}"""
                buffer.valid shouldBe true
              }
            }
          }

          describe("integer value") {
            it("returns complete json") {
              val buffer = new JsonCollectingBuffer()
              buffer.append(ByteString("""{ "age": 101}"""))
              buffer.pop.get.get.utf8String shouldBe """{"age":101}"""
              buffer.valid shouldBe true
            }
          }

          describe("decimal value") {
            it("returns complete json") {
              val buffer = new JsonCollectingBuffer()
              buffer.append(ByteString("""{ "age": 101}"""))
              buffer.pop.get.get.utf8String shouldBe """{"age":101}"""
              buffer.valid shouldBe true
            }
          }

          describe("nested object") {
            it("returns complete json") {
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
              buffer.pop.get.get.utf8String shouldBe """{"name":"john","age":101,"address":{"street":"Straight Street","postcode":1234}}"""
              buffer.valid shouldBe true
            }

            describe("multiple levels") {
              it("returns complete json") {
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
                buffer.pop.get.get.utf8String shouldBe """{"name":"john","age":101,"address":{"street":{"name":"Straight","type":"Avenue"},"postcode":1234}}"""
                buffer.valid shouldBe true
              }
            }
          }

          describe("nested array") {
            it("returns complete json") {
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
              buffer.pop.get.get.utf8String shouldBe """{"name":"john","things":[1,"hey",3,"there"]}"""
              buffer.valid shouldBe true
            }
          }

          describe("complex object graph") {
            it("returns complete json") {
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
              buffer.pop.get.get.utf8String shouldBe """{"name":"john","addresses":[{"street":"3 Hopson Street","postcode":"ABC-123","tags":["work","office"],"contactTime":[{"time":"0900-1800","timezone","UTC"}]},{"street":"12 Adielie Road","postcode":"ZZY-888","tags":["home"],"contactTime":[{"time":"0800-0830","timezone","UTC"},{"time":"1800-2000","timezone","UTC"}]}]}"""
              buffer.valid shouldBe true
            }
          }
        }

        describe("multiple fields") {
          it("returns complete json") {
            val buffer = new JsonCollectingBuffer()
            buffer.append(ByteString("""{ "name": "john", "age": 101}"""))
            buffer.pop.get.get.utf8String shouldBe """{"name":"john","age":101}"""
            buffer.valid shouldBe true
          }
        }

        describe("multiple white space characters") {
          it("returns complete json") {
            val buffer = new JsonCollectingBuffer()
            buffer.append(ByteString(
              """
                |
                |
                |{"name":   "john"
                |, "age": 101}""".stripMargin))
            buffer.pop.get.get.utf8String shouldBe """{"name":"john","age":101}"""
            buffer.valid shouldBe true
          }
        }
      }

      describe("multiple objects") {
        it("pops the right object as buffer is filled") {
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

          buffer.pop.get.get.utf8String shouldBe """{"name":"john","age":32}"""
          buffer.pop.get.get.utf8String shouldBe """{"name":"katie","age":25}"""
          buffer.pop.get shouldBe None

          buffer.append(ByteString("""{"name":"jenkins","age": 65"""))
          buffer.pop.get shouldBe None

          buffer.append(ByteString("}"))
          buffer.pop.get.get.utf8String shouldBe """{"name":"jenkins","age":65}"""
        }
      }

      it("returns none until valid json is encountered") {
        val buffer = new JsonCollectingBuffer()

        """{ "name": "john"""".stripMargin.foreach { c ⇒
          buffer.append(ByteString(c))
          buffer.pop.get shouldBe None
        }

        buffer.append(ByteString("}"))
        buffer.pop.get.get.utf8String shouldBe """{"name":"john"}"""
        buffer.valid shouldBe true
      }
    }

    describe("when invalid json is supplied") {
      it("should fail if it's broken from the start") {
        val buffer = new JsonCollectingBuffer()
        buffer.append(ByteString("""THIS IS NOT VALID { "name": "john"}"""))
        buffer.pop.isFailure shouldBe true
        buffer.valid shouldBe false
      }

      it("should fail if it's broken at the end") {
        val buffer = new JsonCollectingBuffer()
        buffer.append(ByteString("""{ "name": "john"} THIS IS NOT VALID"""))
        buffer.pop.isFailure shouldBe true
        buffer.valid shouldBe false
      }
    }
  }
}
