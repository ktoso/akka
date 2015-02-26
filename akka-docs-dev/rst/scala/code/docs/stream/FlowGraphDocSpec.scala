/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val mat = ActorFlowMaterializer()

  "build simple graph" in {
    //format: OFF
    //#simple-flow-graph
    val g = FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._
      val in = Source(1 to 10)
      val out = Sink.ignore

      val bcast = b.add(Broadcast[Int](2))
      val merge = b.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      in ~> f1 ~> bcast.in
                  bcast.out(0) ~> f2 ~> merge.in(0)
                  bcast.out(1) ~> f4 ~> merge.in(1)
                                        merge.out ~> f3 ~> out
    }
    //#simple-flow-graph
    //format: ON

    //#simple-graph-run
    g.run()
    //#simple-graph-run
  }

  "build simple graph without implicits" in {
    //#simple-flow-graph-no-implicits
    val g = FlowGraph.closed() { b =>
      val in = Source(1 to 10)
      val out = Sink.ignore

      val broadcast = b.add(Broadcast[Int](2))
      val merge = b.add(Merge[Int](2))

      val f1 = Flow[Int].map(_ + 10)
      val f3 = Flow[Int].map(_.toString)
      val f2 = Flow[Int].map(_ + 20)

      b.addEdge(b.add(in), broadcast.in)
      b.addEdge(broadcast.out(0), f1, merge.in(0))
      b.addEdge(broadcast.out(1), f2, merge.in(1))
      b.addEdge(merge.out, f3, b.add(out))
    }
    //#simple-flow-graph-no-implicits

    g.run()
  }

  "flow connection errors" in {
    intercept[IllegalArgumentException] {
      //#simple-graph
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._
        val source1 = Source(1 to 10)
        val source2 = Source(1 to 10)

        val zip = b.add(Zip[Int, Int]())

        source1 ~> zip.in0
        source2 ~> zip.in1
        // unconnected zip.out (!) => "must have at least 1 outgoing edge"
      }
      //#simple-graph
    }.getMessage should include("unconnected ports: Zip.out")
  }

  "reusing a flow in a graph" in {
    //#flow-graph-reusing-a-flow

    val topHeadSink = Sink.head[Int]
    val bottomHeadSink = Sink.head[Int]
    val sharedDoubler = Flow[Int].map(_ * 2)

    //#flow-graph-reusing-a-flow

    // format: OFF
    val g =
    //#flow-graph-reusing-a-flow
    FlowGraph.closed(topHeadSink, bottomHeadSink)((_, _)) { implicit b =>
      (topHS, bottomHS) =>
      import FlowGraph.Implicits._
      val broadcast = b.add(Broadcast[Int](2))
      Source.single(1) ~> broadcast.in

      broadcast.out(0) ~> sharedDoubler ~> topHS.inlet
      broadcast.out(1) ~> sharedDoubler ~> bottomHS.inlet
    }
    //#flow-graph-reusing-a-flow
    // format: ON
    val (topFuture, bottomFuture) = g.run()
    Await.result(topFuture, 300.millis) shouldEqual 2
    Await.result(bottomFuture, 300.millis) shouldEqual 2
  }

  "building a reusable component" in {

    //#flow-graph-components-shape
    // A shape represents the input and output ports of a reusable
    // processing module
    case class PriorityWorkerPoolShape[In, Out](
      jobsIn: Inlet[In],
      priorityJobsIn: Inlet[In],
      resultsOut: Outlet[Out]) extends Shape {

      // It is important to provide the list of all input and output
      // ports with a stable order. Duplicates are not allowed.
      override val inlets: immutable.Seq[Inlet[_]] =
        jobsIn :: priorityJobsIn :: Nil
      override val outlets: immutable.Seq[Outlet[_]] =
        resultsOut :: Nil

      // A Shape must be able to create a copy of itself. Basically
      // it means a new instance with copies of the ports
      override def deepCopy() = PriorityWorkerPoolShape(
        new Inlet[In](jobsIn.toString),
        new Inlet[In](priorityJobsIn.toString),
        new Outlet[Out](resultsOut.toString))

      // A Shape must also be able to create itself from existing ports
      override def copyFromPorts(
        inlets: immutable.Seq[Inlet[_]],
        outlets: immutable.Seq[Outlet[_]]) = {
        assert(inlets.size == this.inlets.size)
        assert(outlets.size == this.outlets.size)
        // This is why order matters when overriding inlets and outlets
        PriorityWorkerPoolShape(inlets(0), inlets(1), outlets(0))
      }
    }
    //#flow-graph-components-shape

    //#flow-graph-components-create
    object PriorityWorkerPool {
      def apply[In, Out](
        worker: Flow[In, Out, _],
        workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], Unit] = {

        FlowGraph.partial() { implicit b ⇒
          import FlowGraph.Implicits._

          val priorityMerge = b.add(MergePreferred[In](1))
          val balance = b.add(Balance[In](workerCount))
          val resultsMerge = b.add(Merge[Out](workerCount))

          // After merging priority and ordinary jobs, we feed them to the balancer
          priorityMerge ~> balance

          // Wire up each of the outputs of the balancer to a worker flow
          // then merge them back
          for (i <- 0 until workerCount)
            balance.out(i) ~> worker ~> resultsMerge.in(i)

          // We now expose the input ports of the priorityMerge and the output
          // of the resultsMerge as our PriorityWorkerPool ports
          // -- all neatly wrapped in our domain specific Shape
          PriorityWorkerPoolShape(
            jobsIn = priorityMerge.in(0),
            priorityJobsIn = priorityMerge.preferred,
            resultsOut = resultsMerge.out)
        }

      }

    }
    //#flow-graph-components-create

    def println(s: Any): Unit = ()

    //#flow-graph-components-use
    val worker1 = Flow[String].map("step 1 " + _)
    val worker2 = Flow[String].map("step 2 " + _)

    FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._

      val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
      val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

      Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
      Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

      priorityPool1.resultsOut ~> priorityPool2.jobsIn
      Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

      priorityPool2.resultsOut ~> Sink.foreach(println)
    }.run()
    //#flow-graph-components-use

    //#flow-graph-components-shape2
    import FanInShape.Name
    import FanInShape.Init

    case class PriorityWorkerPoolShape2[In, Out](
      _init: Init[Out] = Name("PriorityWorkerPool")) extends FanInShape2[In, In, Out](_init) {

      def jobsIn: Inlet[In] = in0
      def priorityJobsIn: Inlet[In] = in1
      def resultsOut: Outlet[Out] = out
    }
    //#flow-graph-components-shape2

  }

}
