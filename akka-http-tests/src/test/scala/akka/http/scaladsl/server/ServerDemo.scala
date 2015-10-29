/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.scaladsl.{ Flow, Source }
import spray.json.DefaultJsonProtocol

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

final case class Tweet(id_str: String, text: String)

trait MyJsonProtocols extends DefaultJsonProtocol with JsonEntityStreamingSupport {
  implicit val tweetFormat = jsonFormat2(Tweet)
}

trait SampleDataGeneration {
  def mkTweet(id: Int): Tweet =
    Tweet(id.toString, s"Hello from $id!")

  def randomTweetsSource(): Source[Tweet, _] =
    Source(() ⇒ Iterator.from(0))
      .map(mkTweet)
}

object ServerDemo extends SampleApp
  with Directives
  with SprayJsonSupport with MyJsonProtocols
  with SampleDataGeneration {

  private val helloWorldRoutes = path("/") {
    complete(Future("Hello world!"))
  }

  // format: OFF
  private val tweetRoutes =
    path("tweets") {
      get {
        val tweets = randomTweetsSource()
          .via(printlnDebug)
        complete(tweets)

          // TODO: renderAsync and un-ordered rendering
          // TODO show TCP buffers
          // TODO show conflate
          // TODO show idle timeouts (in code, and config)
      } ~
      post {
        entity(stream[Tweet]) { tweets: Source[Tweet, _] ⇒
          val eventualCount: Future[String] = // TODO explain future
            tweets
              .via(printlnDebug)
              .take(3)
              .runFold(0)((acc, t) ⇒ acc + 1)
              .map(n ⇒ s"Streamed $n tweets!")

          complete(eventualCount)
        }
      }
    }
  // format: ON

  val routes =
    helloWorldRoutes ~ tweetRoutes

  val bindingFuture = Http().bindAndHandle(routes, interface = "localhost", port = 8080)

  // ----------- less interesting details (shutting down and helpers) -----------
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.shutdown())

  // DON'T DO THIS FOR REAL :-)
  def printlnDebug[T]: Flow[T, T, Unit] =
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

