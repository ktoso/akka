/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.unmarshalling._
import akka.stream.scaladsl.Flow
import spray.json.{ RootJsonFormat, DefaultJsonProtocol }

import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

final case class TwitterStatus(text: String)

trait TwitterJsonProtocols extends DefaultJsonProtocol {
  implicit val statusFormat: RootJsonFormat[TwitterStatus] = jsonFormat1(TwitterStatus)
  implicit def twitterStatus: FromEntityUnmarshaller[TwitterStatus] = SprayJsonSupport.sprayJsonUnmarshaller[TwitterStatus]
}

//noinspection FieldFromDelayedInit
object ClientDemo extends SampleApp
  with SprayJsonSupport with TwitterJsonProtocols
  with DemoClientAuthSettings
  with JsonEntityStreamingSupport
  with RequestBuilding {

  // Authorization: OAuth
  val req = Get("https://stream.twitter.com/1.1/statuses/filter.json" +
    """?track=javaone%2Cscala%2Cjava%2Cgroovy%2Cjruby%2Cruby""")
    .withHeaders(twitterAuthorization)

  println("Request = " + req + "\n\n")

  Http().singleRequest(req) onComplete {
    case Success(res) if res.status == StatusCodes.OK ⇒
      println("Response = " + req + "\n\n")

      val jsons = res.entity.dataBytes
      jsons.via(framingOf[TwitterStatus])
        .runForeach { tweet ⇒ println("tweet = " + tweet) }

    case Failure(ex) ⇒
      ex.printStackTrace()
      system.shutdown()
  }

  // -----
  Console.readLine()
  system.shutdown()

  // DON'T DO THIS FOR REAL :-)
  def debug[T]: Flow[T, T, Unit] =
    Flow[T].map { in ⇒
      println("element: " + in)
      in
    }

  // DON'T DO THIS FOR REAL :-)
  def delay[T](t: FiniteDuration): Flow[T, T, Unit] =
    Flow[T].map { in ⇒
      Thread.sleep(t.toMillis)
      in
    }
}
