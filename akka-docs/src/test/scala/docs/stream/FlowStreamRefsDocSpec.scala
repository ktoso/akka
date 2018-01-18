/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream

import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.stream.{ActorMaterializer, StreamRefAttributes}
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import docs.CompileOnlySpec

import scala.concurrent.Future

class FlowStreamRefsDocSpec extends AkkaSpec with CompileOnlySpec {

  "offer a source ref" in {
    //#offer-source
    import akka.pattern._
    import akka.stream.scaladsl.SourceRef

    case class RequestLogs(streamId: Int)
    case class LogsOffer(streamId: Int, sourceRef: SourceRef[String])

    class DataSource extends Actor {

      import context.dispatcher
      implicit val mat = ActorMaterializer()(context)

      def receive = {
        case RequestLogs(streamId) ⇒
          // obtain the source you want to offer:
          val source: Source[String, NotUsed] = streamLogs(streamId)

          // materialize the SourceRef:
          val ref: Future[SourceRef[String]] = source.runWith(SourceRef.sink())

          // wrap the SourceRef in some domain message, such that the sender knows what source it is
          val reply: Future[LogsOffer] = ref.map(LogsOffer(streamId, _))

          // reply to sender
          reply.pipeTo(sender())
      }

      def streamLogs(streamId: Long): Source[String, NotUsed] = ???
    }
    //#offer-source

    implicit val mat = ActorMaterializer()
    //#offer-source-use
    val sourceActor = system.actorOf(Props[DataSource], "dataSource")

    sourceActor ! RequestLogs(1337)
    val offer = expectMsgType[LogsOffer]

    // implicitly converted to a Source:
    offer.sourceRef.runWith(Sink.foreach(println))
    // alternatively explicitly obtain Source from SourceRef:
    // offer.sourceRef.source.runWith(Sink.foreach(println))

    //#offer-source-use
  }

  "offer a sink ref" in {
    //#offer-sink
    import akka.pattern._
    import akka.stream.scaladsl.SinkRef

    case class PrepareUpload(sourceId: String)
    case class MeasurementsSinkReady(sourceId: String, sinkRef: SinkRef[String])

    class DataReceiver extends Actor {

      import context.dispatcher
      implicit val mat = ActorMaterializer()(context)

      def receive = {
        case PrepareUpload(nodeId) ⇒
          // obtain the source you want to offer:
          val sink: Sink[String, NotUsed] = logsSinkFor(nodeId)

          // materialize the SinkRef (the remote is like a source of data for us):
          val ref: Future[SinkRef[String]] = SinkRef.source[String]().to(sink).run()

          // wrap the SinkRef in some domain message, such that the sender knows what source it is
          val reply: Future[MeasurementsSinkReady] = ref.map(MeasurementsSinkReady(nodeId, _))

          // reply to sender
          reply.pipeTo(sender())
      }

      def logsSinkFor(nodeId: String): Sink[String, NotUsed] = ???
    }

    //#offer-sink

    implicit val mat = ActorMaterializer()
    def localMetrics(): Source[String, NotUsed] = Source.single("")

    //#offer-sink-use
    val receiver = system.actorOf(Props[DataReceiver], "receiver")

    receiver ! PrepareUpload("system-42-tmp")
    val ready = expectMsgType[MeasurementsSinkReady]

    // stream local metrics to Sink's origin:
    localMetrics().runWith(ready.sinkRef)
    //#offer-sink-use
  }

  "show how to configure timeouts with attrs" in {
    //#attr-sub-timeout

    implicit val mat: ActorMaterializer = null
    // configure the timeout for source
    import scala.concurrent.duration._
    import akka.stream.StreamRefAttributes

    // configuring SourceRef.sink (notice that we apply the attributes to the Sink!):
    Source.repeat("hello")
      .runWith(SourceRef.sink[String]().addAttributes(StreamRefAttributes.subscriptionTimeout(5.seconds)))

    // configuring SinkRef.source:
    SinkRef.source[String].addAttributes(StreamRefAttributes.subscriptionTimeout(5.seconds))
      .runWith(Sink.ignore) // just an example
    //#attr-sub-timeout
  }

}
