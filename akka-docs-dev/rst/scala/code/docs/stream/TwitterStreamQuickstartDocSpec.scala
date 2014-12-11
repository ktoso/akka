/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

//#imports

import java.util.Date

import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.MaterializedMap
import akka.stream.scaladsl.RunnableFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import concurrent.Await
import concurrent.Future

//#imports

import akka.stream.testkit.AkkaSpec

// TODO replace ⇒ with => and disable this intellij setting
class TwitterStreamQuickstartDocSpec extends AkkaSpec {

  implicit val executionContext = system.dispatcher

  //#model
  final case class Author(handle: String)
  val AkkaTeam = Author("akkateam")

  final case class Hashtag(name: String)

  final case class Tweet(author: Author, timestamp: Long, body: String) {
    def hashtags: List[Hashtag] =
      body.split(" ").toList.collect { case t if t.startsWith("#") ⇒ Hashtag(t) }
  }
  //#model

  trait Example0 {
    //#tweet-source
    val tweets: Source[Tweet]
    //#tweet-source
  }

  trait Example1 {
    trait X {
      //#materializer-setup
      implicit val syste = ActorSystem("reactive-tweets")
      //#materializer-setup
    }
    //#materializer-setup
    implicit val mat = FlowMaterializer()
    //#materializer-setup
  }

  val tweets = Source(
    Tweet(Author("rkuhn"), (new Date).getTime, "#akka rocks!") ::
      Tweet(Author("drewhk"), (new Date).getTime, "#akka!") ::
      Tweet(Author("ktosopl"), (new Date).getTime, "#akka on the rocks!") ::
      Nil)

  implicit val mat = FlowMaterializer()

  "filter and map" in {
    //#authors-filter-map
    val authors: Source[Author] =
      tweets
        .filter(_.hashtags.contains("#akka"))
        .map(_.author)
    //#authors-filter-map

    trait Example3 {
      //#authors-collect
      val authors: Source[Author] =
        tweets.collect { case t if t.hashtags.contains("#akka") ⇒ t.author }
      //#authors-collect
    }

    //#authors-foreachsink-println
    authors.runWith(Sink.foreach(println))
    //#authors-foreachsink-println

    //#authors-foreach-println
    authors.foreach(println)
    //#authors-foreach-println
  }

  "mapConcat hashtags" in {
    //#hashtags-mapConcat
    val hashtags: Source[Hashtag] = tweets.mapConcat(_.hashtags)
    //#hashtags-mapConcat
  }

  "simple broadcast" in {
    trait X {
      //#flow-graph-broadcast
      val writeAuthors: Sink[Author] = ???
      val writeHashtags: Sink[Hashtag] = ???
      //#flow-graph-broadcast
    }

    val writeAuthors: Sink[Author] = Sink.ignore
    val writeHashtags: Sink[Hashtag] = Sink.ignore

    // format: OFF
    //#flow-graph-broadcast
    val g = FlowGraph { implicit builder ⇒
      import FlowGraphImplicits._

      val b = Broadcast[Tweet]
      tweets ~> b ~> Flow[Tweet].map(_.author) ~> writeAuthors
                b ~> Flow[Tweet].mapConcat(_.hashtags) ~> writeHashtags
    }
    g.run()
    //#flow-graph-broadcast
    // format: ON
  }

  "slowProcessing" in {
    def slowComputation(t: Tweet): Long = {
      Thread.sleep(500) // act as if performing some heavy computation
      42
    }

    //#tweets-slow-consumption-dropHead
    tweets
      .buffer(10, OverflowStrategy.dropHead)
      .map(slowComputation)
      .runWith(Sink.ignore)
    //#tweets-slow-consumption-dropHead
  }

  "backpressure by readline" in {
    trait X {
      import scala.concurrent.duration._

      //#backpressure-by-readline
      val completion: Future[Unit] =
        Source(1 to 10)
          .map(i ⇒ { println(s"map => $i"); i })
          .foreach { i ⇒ readLine(s"Element = $i; continue reading? [press enter]\n") }

      Await.ready(completion, 1.minute)
      //#backpressure-by-readline
    }
  }

  "count elements on finite stream" in {
    //#tweets-fold-count
    val sumSink = Sink.fold[Int, Int](0)(_ + _)

    val counter: RunnableFlow = tweets.map(t ⇒ 1).to(sumSink)
    val map: MaterializedMap = counter.run()

    val sum: Future[Int] = map.get(sumSink)

    sum.map { c ⇒ println(s"Total tweets processed: $c") }
    //#tweets-fold-count

    new AnyRef {
      //#tweets-fold-count-oneline
      val sum: Future[Int] = tweets.map(t ⇒ 1).runWith(sumSink)
      //#tweets-fold-count-oneline
    }
  }

}
