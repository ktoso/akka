/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.scaladsl.PartialFlowGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.UndefinedSink
import akka.stream.scaladsl.UndefinedSource
import akka.stream.scaladsl.Zip
import akka.stream.scaladsl.ZipWith

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.testkit.AkkaSpec

// TODO replace ⇒ with => and disable this intellij setting
class StreamPartialFlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val mat = FlowMaterializer()

  "build with open ports" in {
    //#simple-partial-flow-graph
    val in1 = UndefinedSource[Int]
    val in2 = UndefinedSource[Int]
    val in3 = UndefinedSource[Int]
    val out = UndefinedSink[Int]

    val pickMaxOfThree: PartialFlowGraph = PartialFlowGraph { implicit b ⇒
      import FlowGraphImplicits._
      val pickMaxOfPair = Flow[(Int, Int)].map { case (a, b) ⇒ math.max(a, b) }

      val zip1 = Zip[Int, Int]
      val zip2 = Zip[Int, Int]

      in1 ~> zip1.left
      in2 ~> zip1.right

      zip1.out ~> pickMaxOfPair ~> zip2.left
      in3 ~> zip2.right
      zip2.out ~> pickMaxOfPair ~> out
    }

    val resultSink = Sink.head[Int]

    val g = FlowGraph { implicit b ⇒
      // import the partial flow graph explicitly
      b.importPartialFlowGraph(pickMaxOfThree)

      b.attachSource(in1, Source(1 :: Nil))
      b.attachSource(in2, Source(2 :: Nil))
      b.attachSource(in3, Source(3 :: Nil))
      b.attachSink(out, resultSink)
    }

    val materialized = g.run()
    val max: Future[Int] = materialized.get(resultSink)
    Await.result(max, 100.millis) should equal(3)
    //#simple-partial-flow-graph

    val g2 =
      //#simple-partial-flow-graph-import-shorthand
      FlowGraph(pickMaxOfThree) { implicit b ⇒
        b.attachSource(in1, Source(1 :: Nil))
        b.attachSource(in2, Source(2 :: Nil))
        b.attachSource(in3, Source(3 :: Nil))
        b.attachSink(out, resultSink)
      }
    //#simple-partial-flow-graph-import-shorthand
    val materialized2 = g.run()
    val max2: Future[Int] = materialized2.get(resultSink)
    Await.result(max2, 100.millis) should equal(3)
  }

  "build source from partial flow graph" in {
    //#source-from-partial-flow-graph
    val pairs: Source[(Int, Int)] = Source() { implicit b ⇒
      import FlowGraphImplicits._

      // prepare graph elements
      val undefinedSink = UndefinedSink[(Int, Int)]
      val zip = Zip[Int, Int]
      def ints = Source(() ⇒ Iterator.from(1))

      // connect the graph
      ints ~> Flow[Int].filter(_ % 2 != 0) ~> zip.left
      ints ~> Flow[Int].filter(_ % 2 == 0) ~> zip.right
      zip.out ~> undefinedSink

      // expose undefined sink
      undefinedSink
    }

    val even: Future[(Int, Int)] = pairs.runWith(Sink.head)
    Await.result(even, 100.millis) should equal(1 → 2)
    //#source-from-partial-flow-graph
  }

  "build flow from partial flow graph" in {
    //#flow-from-partial-flow-graph
    val pairUpWithToString = Flow() { implicit b ⇒
      import FlowGraphImplicits._

      // prepare graph elements
      val undefinedSource = UndefinedSource[Int]
      val undefinedSink = UndefinedSink[(Int, String)]

      val broadcast = Broadcast[Int]
      val zip = Zip[Int, String]

      // connect the graph
      undefinedSource ~> broadcast
      broadcast ~> Flow[Int].map(identity) ~> zip.left
      broadcast ~> Flow[Int].map(_.toString) ~> zip.right
      zip.out ~> undefinedSink

      // expose undefined ports
      (undefinedSource, undefinedSink)
    }
    val (_, matSink: Future[(Int, String)]) = pairUpWithToString.runWith(Source(List(1)), Sink.head)
    Await.result(matSink, 100.millis) should equal(1 → "1")
    //#flow-from-partial-flow-graph
  }
}
