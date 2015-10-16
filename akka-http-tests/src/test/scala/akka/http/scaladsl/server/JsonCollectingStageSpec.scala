package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ WordSpec, Matchers, FunSpec }

import scala.collection.immutable.Seq

class JsonCollectingStageSpec extends WordSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

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
        .transform(() ⇒ new JsonCollectingStage())
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{"name":"john"}""",
        """{"name":"jack"}""",
        """{"name":"katie"}""")
    }

    "parse line delimited" in {
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

    "parse comma delimited" in {
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

    "parse chunks successfully" in {
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

