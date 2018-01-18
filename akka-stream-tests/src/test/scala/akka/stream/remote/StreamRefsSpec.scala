/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, ActorSystemImpl, Identify, Props }
import akka.event.Logging
import akka.stream.{ ActorAttributes, ActorMaterializer, StreamRefAttributes, StreamRefSettings }
import akka.stream.scaladsl.{ Keep, Sink, SinkRef, Source, SourceRef }
import akka.stream.testkit.scaladsl._
import akka.testkit.{ AkkaSpec, ImplicitSender, SocketUtil, TestKit, TestProbe }
import akka.util.ByteString
import com.typesafe.config._
import akka.pattern._
import akka.stream.StreamRefs.CyclicMaterializationAttemptException
import akka.stream.testkit.TestPublisher

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NoStackTrace

object StreamRefsSpec {

  object DatasourceActor {
    def props(probe: ActorRef): Props =
      Props(new DatasourceActor(probe))
        .withDispatcher("akka.test.stream-dispatcher")
  }

  class DatasourceActor(probe: ActorRef) extends Actor with ActorLogging {
    implicit val mat = ActorMaterializer()

    def receive = {
      case "give" ⇒
        /*
         * Here we're able to send a source to a remote recipient
         *
         * For them it's a Source; for us it is a Sink we run data "into"
         */
        val source: Source[String, NotUsed] = Source(List("hello", "world"))
        val ref: Future[SourceRef[String]] = source.runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds) // TODO avoid the await

      case "give-infinite" ⇒
        val source: Source[String, NotUsed] = Source.fromIterator(() ⇒ Iterator.from(1)).map("ping-" + _)
        val ref: Future[SourceRef[String]] = source.runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds) // TODO avoid the await

      case "give-fail" ⇒
        val ref = Source.failed[String](new Exception("Booooom!") with NoStackTrace)
          .runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds) // TODO avoid the await

      case "give-complete-asap" ⇒
        val ref = Source.empty
          .runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds) // TODO avoid the await

      case "give-subscribe-timeout" ⇒
        val ref = Source.repeat("is anyone there?")
          .toMat(SourceRef.sink())(Keep.right) // attributes like this so they apply to the SourceRef.sink
          .withAttributes(StreamRefAttributes.subscriptionTimeout(500.millis))
          .run()

        sender() ! Await.result(ref, 10.seconds) // TODO avoid the await

      //      case "send-bulk" ⇒
      //        /*
      //         * Here we're able to send a source to a remote recipient
      //         * The source is a "bulk transfer one, in which we're ready to send a lot of data"
      //         *
      //         * For them it's a Source; for us it is a Sink we run data "into"
      //         */
      //        val source: Source[ByteString, NotUsed] = Source.single(ByteString("huge-file-"))
      //        val ref: SourceRef[ByteString] = source.runWith(SourceRef.bulkTransfer())
      //        sender() ! BulkSourceMsg(ref)

      case "receive" ⇒
        /*
         * We write out code, knowing that the other side will stream the data into it.
         *
         * For them it's a Sink; for us it's a Source.
         */
        val sink: Future[SinkRef[String]] =
          SinkRef.source[String]
            .to(Sink.actorRef(probe, "<COMPLETE>"))
            .run()

        // FIXME we want to avoid forcing people to do the Future here
        sender() ! Await.result(sink, 10.seconds)

      case "receive-subscribe-timeout" ⇒
        val sink = SinkRef.source[String]
          .withAttributes(StreamRefAttributes.subscriptionTimeout(500.millis))
          .to(Sink.actorRef(probe, "<COMPLETE>"))
          .run()

        // FIXME we want to avoid forcing people to do the Future here
        sender() ! Await.result(sink, 10.seconds)

      case "receive-32" ⇒
        val (sink, driver) = SinkRef.source[String]
          .toMat(TestSink.probe(context.system))(Keep.both)
          .run()

        import context.dispatcher
        Future {
          driver.ensureSubscription()
          driver.request(2)
          driver.expectNext()
          driver.expectNext()
          driver.expectNoMessage(100.millis)
          driver.request(30)
          driver.expectNextN(30)

          "<COMPLETED>"
        } pipeTo probe

        // FIXME we want to avoid forcing people to do the Future here
        sender() ! Await.result(sink, 10.seconds)

      //      case "receive-bulk" ⇒
      //        /*
      //         * We write out code, knowing that the other side will stream the data into it.
      //         * This will open a dedicated connection per transfer.
      //         *
      //         * For them it's a Sink; for us it's a Source.
      //         */
      //        val sink: SinkRef[ByteString] =
      //          SinkRef.bulkTransferSource()
      //            .to(Sink.actorRef(probe, "<COMPLETE>"))
      //            .run()
      //
      //
      //        sender() ! BulkSinkMsg(sink)
    }

  }

  // -------------------------

  final case class SourceMsg(dataSource: SourceRef[String])
  final case class BulkSourceMsg(dataSource: SourceRef[ByteString])
  final case class SinkMsg(dataSink: SinkRef[String])
  final case class BulkSinkMsg(dataSink: SinkRef[ByteString])

  def config(): Config = {
    val address = SocketUtil.temporaryServerAddress()
    ConfigFactory.parseString(
      s"""
    akka {
      loglevel = INFO

      actor {
        provider = remote
        serialize-messages = off
      }

      remote.netty.tcp {
        port = ${address.getPort}
        hostname = "${address.getHostName}"
      }
    }
  """).withFallback(ConfigFactory.load())
  }
}

class StreamRefsSpec(config: Config) extends AkkaSpec(config) with ImplicitSender {
  import StreamRefsSpec._

  def this() {
    this(StreamRefsSpec.config())
  }

  val remoteSystem = ActorSystem("RemoteSystem", StreamRefsSpec.config())
  implicit val mat = ActorMaterializer()

  override protected def beforeTermination(): Unit =
    TestKit.shutdownActorSystem(remoteSystem)

  val p = TestProbe()

  // obtain the remoteActor ref via selection in order to use _real_ remoting in this test
  val remoteActor = {
    val it = remoteSystem.actorOf(DatasourceActor.props(p.ref), "remoteActor")
    val remoteAddress = remoteSystem.asInstanceOf[ActorSystemImpl].provider.getDefaultAddress
    system.actorSelection(it.path.toStringWithAddress(remoteAddress)) ! Identify("hi")
    expectMsgType[ActorIdentity].ref.get
  }

  "A SourceRef" must {

    "send messages via remoting" in {
      remoteActor ! "give"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      p.expectMsg("hello")
      p.expectMsg("world")
      p.expectMsg("<COMPLETE>")
    }

    "fail when remote source failed" in {
      remoteActor ! "give-fail"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      val f = p.expectMsgType[Failure]
      f.cause.getMessage should include("Remote stream (")
      // actor name here, for easier identification
      f.cause.getMessage should include("failed, reason: Booooom!")
    }

    "complete properly when remote source is empty" in {
      // this is a special case since it makes sure that the remote stage is still there when we connect to it

      remoteActor ! "give-complete-asap"
      val sourceRef = expectMsgType[SourceRef[String]]

      sourceRef
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      p.expectMsg("<COMPLETE>")
    }

    "respect back-pressure from (implied by target Sink)" in {
      remoteActor ! "give-infinite"
      val sourceRef = expectMsgType[SourceRef[String]]

      val probe = sourceRef
        .runWith(TestSink.probe)

      probe.ensureSubscription()
      probe.expectNoMessage(100.millis)

      probe.request(1)
      probe.expectNext("ping-1")
      probe.expectNoMessage(100.millis)

      probe.request(20)
      probe.expectNextN((1 to 20).map(i ⇒ "ping-" + (i + 1)))
      probe.cancel()

      // since no demand anyway
      probe.expectNoMessage(100.millis)

      // should not cause more pulling, since we issued a cancel already
      probe.request(10)
      probe.expectNoMessage(100.millis)
    }

    "receive timeout if subscribing too late to the source ref" in {
      remoteActor ! "give-subscribe-timeout"
      val remoteSource: SourceRef[String] = expectMsgType[SourceRef[String]]

      // not materializing it, awaiting the timeout...
      Thread.sleep(800) // the timeout is 500ms

      val probe = remoteSource
        .runWith(TestSink.probe[String](system))

      //      val failure = p.expectMsgType[Failure]
      //      failure.cause.getMessage should include("[SourceRef-0] Remote side did not subscribe (materialize) handed out Sink reference")

      // the local "remote sink" should cancel, since it should notice the origin target actor is dead
      probe.ensureSubscription()
      val ex = probe.expectError()
      ex.getMessage should include("has terminated! Tearing down this side of the stream as well.")
    }

    // TODO would be nice to enforce this statically, but then we get into hell type wise... it can't just be the simple "just a source ref"...
    "if obtained from remote, materialized value should fail materialized value immediately to avoid weird 'cycles' in materialization" in {
      remoteActor ! "give"
      val sourceRef = expectMsgType[SourceRef[String]]

      val f = sourceRef.to(Sink.ignore).run()
      val ex = intercept[Exception] {
        f.futureValue
      }

      ex.getMessage should include("This SourceRef will never materialize its materialized value (SinkRef), since it was *already* the")
    }

  }

  "A SinkRef" must {

    "receive elements via remoting" in {

      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      Source("hello" :: "world" :: Nil)
        .to(remoteSink)
        .run()

      p.expectMsg("hello")
      p.expectMsg("world")
      p.expectMsg("<COMPLETE>")
    }

    "fail origin if remote Sink gets a failure" in {

      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      val remoteFailureMessage = "Booom!"
      Source.failed(new Exception(remoteFailureMessage))
        .to(remoteSink)
        .run()

      val f = p.expectMsgType[akka.actor.Status.Failure]
      f.cause.getMessage should include(s"Remote stream (")
      // actor name ere, for easier identification
      f.cause.getMessage should include(s"failed, reason: $remoteFailureMessage")
    }

    "receive hundreds of elements via remoting" in {
      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      val msgs = (1 to 100).toList.map(i ⇒ s"payload-$i")

      Source(msgs)
        .runWith(remoteSink)

      msgs.foreach(t ⇒ p.expectMsg(t))
      p.expectMsg("<COMPLETE>")
    }

    "receive timeout if subscribing too late to the sink ref" in {
      remoteActor ! "receive-subscribe-timeout"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      // not materializing it, awaiting the timeout...
      Thread.sleep(800) // the timeout is 500ms

      val probe = TestSource.probe[String](system)
        .to(remoteSink)
        .run()

      val failure = p.expectMsgType[Failure]
      failure.cause.getMessage should include("[SourceRef-0] Remote side did not subscribe (materialize) handed out Sink reference")

      // the local "remote sink" should cancel, since it should notice the origin target actor is dead
      probe.expectCancellation()
    }

    "respect back -pressure from (implied by origin Sink)" in {
      remoteActor ! "receive-32"
      val sinkRef = expectMsgType[SinkRef[String]]

      Source.repeat("hello") runWith sinkRef

      // if we get this message, it means no checks in the request/expect semantics were broken, good!
      p.expectMsg("<COMPLETED>")
    }

    "not allow materializing multiple times" in {
      remoteActor ! "receive"
      val sinkRef = expectMsgType[SinkRef[String]]

      val p1: TestPublisher.Probe[String] = TestSource.probe[String].to(sinkRef).run()
      val p2: TestPublisher.Probe[String] = TestSource.probe[String].to(sinkRef).run()

      p1.ensureSubscription()
      val req = p1.expectRequest()

      // will be cancelled immediately, since it's 2nd:
      p2.ensureSubscription()
      p2.expectCancellation()
    }

    "if obtained from remote, materialized value should fail materialized value immediately to avoid weird 'cycles' in materialization" in {
      remoteActor ! "receive"
      val sinkRef = expectMsgType[SinkRef[String]]

      val f = TestSource.probe[String].runWith(sinkRef)
      val ex = intercept[Exception] {
        f.futureValue
      }

      ex.getMessage should include("This SinkRef will never materialize its materialized value (SourceRef), since it was *already* the")
    }

    "fail local Source when attempting to materialize second time to already active interchange" in {
      remoteActor ! "receive"
      val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]

      val msgs = (1 to 100).toList.map(i ⇒ s"payload-$i")

      val it: Future[SourceRef[String]] = Source(msgs)
        .runWith(remoteSink)

      val pp = TestProbe()
      val i = Await.result(it, 10.seconds)
      Thread.sleep(100) // need a delay to make sure we're "second"
      i.runWith(Sink.actorRef(pp.ref, "<<COMPLETE>>"))

      msgs.foreach(t ⇒ p.expectMsg(t))
      p.expectMsg("<COMPLETE>")

      val f = pp.expectMsgType[akka.actor.Status.Failure]
      f.cause.getMessage should include("nein")

    }

    // FIXME did not get Terminated?
    //    "fail origin if remote Sink is stopped abruptly" in {
    //      val otherSystem = ActorSystem("OtherRemoteSystem", StreamRefsSpec.config())
    //
    //      try {
    //        // obtain the remoteActor ref via selection in order to use _real_ remoting in this test
    //        val remoteActor = {
    //          val it = otherSystem.actorOf(DatasourceActor.props(p.ref), "remoteActor")
    //          val remoteAddress = otherSystem.asInstanceOf[ActorSystemImpl].provider.getDefaultAddress
    //          system.actorSelection(it.path.toStringWithAddress(remoteAddress)) ! Identify("hi")
    //          expectMsgType[ActorIdentity].ref.get
    //        }
    //
    //        remoteActor ! "receive"
    //        val remoteSink: SinkRef[String] = expectMsgType[SinkRef[String]]
    //
    //        val otherMat = ActorMaterializer()(otherSystem)
    //        Source.maybe[String] // not emitting anything
    //          .to(remoteSink)
    //          .run()(otherMat)
    //
    //        // and the system crashes; which should cause abrupt termination in the stream
    //        Thread.sleep(300)
    //        otherMat.shutdown()
    //
    //        val f = p.expectMsgType[akka.actor.Status.Failure]
    //        f.cause.getMessage should ===(s"Remote Sink failed, reason:")
    //      } finally TestKit.shutdownActorSystem(otherSystem)
    //    }

  }

}
