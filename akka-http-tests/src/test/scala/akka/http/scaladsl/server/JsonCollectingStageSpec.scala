package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, FunSpec }

import scala.collection.immutable.Seq

class JsonCollectingStageSpec extends FunSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  describe("multiple json") {
    describe("array") {
      it("produces source of valid json string sequences") {
        val input =
          """
            |[
            | { "name": "john" },
            | { "name": "jack" },
            | { "name": "katie" }
            |]
          """.stripMargin

        val result = Source.single(ByteString(input))
          .transform(() ⇒ new JsonCollectingStage())
          .runFold(Seq.empty[String]) {
            case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
          }

        result.futureValue shouldBe Seq(
          """{"name":"john"}""",
          """{"name":"jack"}""",
          """{"name":"katie"}""")
      }
    }

    describe("line break delimited string") {
      it("produces source of valid json string sequences") {
        val input =
          """
            | { "name": "john" }
            | { "name": "jack" }
            | { "name": "katie" }
          """.stripMargin

        val result = Source.single(ByteString(input))
          .transform(() ⇒ new JsonCollectingStage())
          .runFold(Seq.empty[String]) {
            case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
          }

        result.futureValue shouldBe Seq(
          """{"name":"john"}""",
          """{"name":"jack"}""",
          """{"name":"katie"}""")
      }
    }

    describe("comma delimited string") {
      it("produces source of valid json string sequences") {
        val input =
          """
            | { "name": "john" }, { "name": "jack" }, { "name": "katie" }
          """.stripMargin

        val result = Source.single(ByteString(input))
          .transform(() ⇒ new JsonCollectingStage())
          .runFold(Seq.empty[String]) {
            case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
          }

        result.futureValue shouldBe Seq(
          """{"name":"john"}""",
          """{"name":"jack"}""",
          """{"name":"katie"}""")
      }
    }
  }

  describe("chunks") {
    it("produces source of valid json string sequences") {
      val input: Seq[ByteString] = Seq(
        """
          |[
          |  { "name": "john"
        """.stripMargin,
        """
          |},
        """.stripMargin,
        """{ "na""",
        """me": "jack""",
        """"}]"""").map(ByteString(_))

      val result = Source.apply(input)
        .transform(() ⇒ new JsonCollectingStage())
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{"name":"john"}""",
        """{"name":"jack"}""")
    }
  }

}

