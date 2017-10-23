/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.testkit.{AkkaSpec, ImplicitSender}
import akka.util.ByteString

object SourceRefSpec {
  class DatasourceActor extends Actor {
    def receive = {
      case "send" ⇒
        /*
         * Here we're able to send a source to a remote recipient
         *
         * For them it's a Source; for us it is a Sink we run data "into"
         */
        val source: Source[String, NotUsed] = Source.single("huge-file-")
        val ref: SourceRef[String] = source.runWith(SourceRef())
        remoteActor ! SourceMsg(ref)

      case "send-bulk" ⇒
        /*
         * Here we're able to send a source to a remote recipient
         * The source is a "bulk transfer one, in which we're ready to send a lot of data"
         *
         * For them it's a Source; for us it is a Sink we run data "into"
         */
        val source: Source[ByteString, NotUsed] = Source.single(ByteString("huge-file-"))
        val ref: SourceRef[ByteString] = source.runWith(SourceRef.bulkTransfer())
        remoteActor ! BulkSourceMsg(ref)

      case "receive" ⇒
        /*
         * We write out code, knowing that the other side will stream the data into it.
         *
         * For them it's a Sink; for us it's a Source.
         */
        val sink: SinkRef[String] =
          SinkRef.source[String]
            .to(Sink.foreach { t ⇒ println(t) })
            .run()

        remoteActor ! SinkMsg(sink)

      case "receive-bulk" ⇒
        /*
         * We write out code, knowing that the other side will stream the data into it.
         * This will open a dedicated connection per transfer.
         *
         * For them it's a Sink; for us it's a Source.
         */
        val sink: SinkRef[ByteString] =
          SinkRef.bulkTransferSource()
            .to(FileIO.toPath(Paths.get("/tmp/example")))
            .run()

        remoteActor ! BulkSinkMsg(sink)
    }

    private def remoteActor = {
      sender()
    }
  }

  // -------------------------

  case class SourceMsg(dataSource: SourceRef[String])
  case class BulkSourceMsg(dataSource: SourceRef[ByteString])

  case class SinkMsg(dataSink: SinkRef[String])
  case class BulkSinkMsg(dataSink: SinkRef[ByteString])

}

class SourceRefSpec extends AkkaSpec with ImplicitSender {
  import SourceRefSpec._

  val remoteSystem = ActorSystem("RemoteSystem")
  implicit val mat = ActorMaterializer()

  "A SourceRef" must {

    //    "work" in {
    //      val actor = remoteSystem.actorOf(Props(classOf[DatasourceActor]), "actor")
    //      actor ! "give"
    //      val SourceMsg(sourceRef) = expectMsgType[SourceMsg]
    //
    //      sourceRef.map(_ + 1).runForeach { x ⇒
    //        println(x)
    //      }
    //    }

  }

  "A SinkRef" must {

    "work" in {
      val actor = system.actorOf(Props(classOf[DatasourceActor]), "actor")
      actor ! "receive"
      val SinkMsg(remoteSink) = expectMsgType[SinkMsg]

      Source.single("hello")
        .to(remoteSink)
        .run()

    }

  }

}
