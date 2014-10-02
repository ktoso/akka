/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.model.HttpEntity._
import akka.stream.FlowMaterializer
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.reactivestreams.Publisher
import org.scalatest.matchers.Matcher
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, MustMatchers }

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await, Promise }

class HttpEntitySpec extends FreeSpec with MustMatchers with BeforeAndAfterAll {
  val tpe: ContentType = ContentTypes.`application/octet-stream`
  val abc = ByteString("abc")
  val de = ByteString("de")
  val fgh = ByteString("fgh")
  val ijk = ByteString("ijk")

  val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  implicit val materializer = FlowMaterializer()
  override def afterAll() = system.shutdown()

  "HttpEntity" - {
    "support dataBytes" - {
      "Strict" in {
        Strict(tpe, abc) must collectBytesTo(abc)
      }
      "Default" in {
        Default(tpe, 11, publisher(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, publisher(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk))) must collectBytesTo(abc, fgh, ijk)
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must collectBytesTo(abc, fgh, ijk)
      }
    }
    "support toStrict" - {
      "Strict" in {
        Strict(tpe, abc) must strictifyTo(Strict(tpe, abc))
      }
      "Default" in {
        Default(tpe, 11, publisher(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, publisher(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk))) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Infinite data stream" in {
        val neverCompleted = Promise[ByteString]()
        val stream: Publisher[ByteString] = Flow(neverCompleted.future).toPublisher()
        intercept[TimeoutException] {
          Await.result(Default(tpe, 42, stream).toStrict(100.millis), 150.millis)
        }.getMessage must be("HttpEntity.toStrict timed out after 100 milliseconds while still waiting for outstanding data")
      }
      "only once, otherwise error" in {
        def getResponseBody(entity: ResponseEntity): Future[String] =
          entity.toStrict(100.millis).map { e ⇒
            e.data.decodeString(e.contentType.charset.nioCharset.toString)
          }

        val entity = Chunked(tpe, Flow(List(Chunk(abc), Chunk(de), Chunk(fgh), LastChunk).iterator).toPublisher())

        val first = getResponseBody(entity)
        Await.result(first, 150.millis) must be("abcdefgh")

        val second = getResponseBody(entity)
        Await.result(second, 150.millis) must be("abcdefgh")
      }
    }
  }

  def publisher[T](elems: T*) = SynchronousPublisherFromIterable(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity ⇒
      val future = Flow(entity.dataBytes).grouped(1000).toFuture()
      Await.result(future, 250.millis)
    }

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(x ⇒ Await.result(x.toStrict(250.millis), 250.millis))
}
