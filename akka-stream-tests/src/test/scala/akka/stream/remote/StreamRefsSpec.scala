/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote

import akka.NotUsed
import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorIdentity, ActorLogging, ActorRef, ActorSystem, ActorSystemImpl, Identify, Props }
import akka.stream.ActorMaterializer
import akka.stream.remote.scaladsl.{ SinkRef, SourceRef }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.{ AkkaSpec, ImplicitSender, SocketUtil, TestKit, TestProbe }
import akka.util.ByteString
import com.typesafe.config._

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

        // FIXME we want to avoid forcing people to do the Future here
        sender() ! Await.result(ref, 10.seconds)

      case "give-fail" ⇒
        val ref = Source.failed[String](new Exception("Booooom!") with NoStackTrace)
          .runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds)

      case "give-complete-asap" ⇒
        val ref = Source.empty
          .runWith(SourceRef.sink())

        sender() ! Await.result(ref, 10.seconds)

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

      Source.fromGraph(sourceRef)
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      p.expectMsg("hello")
      p.expectMsg("world")
      p.expectMsg("<COMPLETE>")
    }

    "fail when remote source failed" in {
      remoteActor ! "give-fail"
      val sourceRef = expectMsgType[SourceRef[String]]

      Source.fromGraph(sourceRef)
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

      Source.fromGraph(sourceRef)
        .runWith(Sink.actorRef(p.ref, "<COMPLETE>"))

      val f = p.expectMsg("<COMPLETE>")
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
        .to(remoteSink)
        .run()

      msgs.foreach(t ⇒ p.expectMsg(t))
      p.expectMsg("<COMPLETE>")
    }

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
