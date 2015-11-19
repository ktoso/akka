/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import akka.stream.stage.{ InHandler, GraphStageLogic, GraphStage }
import akka.util.Reflect

import language.existentials
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.collection.immutable
import akka.actor._
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, HttpRequest }
import akka.http.scaladsl.util.FastFuture
import akka.http.ConnectionPoolSettings
import akka.http.impl.util._
import akka.stream.impl.{ SubscribePending, ExposedPublisher, ActorProcessor }
import akka.stream.actor._
import akka.stream.scaladsl._
import akka.stream._

private object PoolSlot {
  import PoolFlow.{ RequestContext, ResponseContext }

  sealed trait ProcessorOut
  final case class ResponseDelivery(response: ResponseContext) extends ProcessorOut
  sealed trait RawSlotEvent extends ProcessorOut
  sealed trait SlotEvent extends RawSlotEvent
  object SlotEvent {
    final case class RequestCompletedFuture(future: Future[RequestCompleted]) extends RawSlotEvent
    final case class RetryRequest(rc: RequestContext) extends RawSlotEvent
    final case class RequestCompleted(slotIx: Int) extends SlotEvent
    final case class Disconnected(slotIx: Int, failedRequests: Int) extends SlotEvent
  }

  private val slotProcessorActorName = new SeqActorName("SlotProcessor")

  /*
    Stream Setup
    ============

    Request-   +-----------+              +-------------+              +-------------+     +------------+ 
    Context    | Slot-     |  List[       |   flatten   |  Processor-  |   doubler   |     | SlotEvent- |  Response-
    +--------->| Processor +------------->| (MapConcat) +------------->| (MapConcat) +---->| Split      +------------->
               |           |  Processor-  |             |  Out         |             |     |            |  Context                                  
               +-----------+  Out]        +-------------+              +-------------+     +-----+------+                                    
                                                                                                 | RawSlotEvent                                                    
                                                                                                 | (to Conductor
                                                                                                 |  via slotEventMerge)
                                                                                                 v 
   */
  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
            remoteAddress: InetSocketAddress, // TODO: remove after #16168 is cleared
            settings: ConnectionPoolSettings)(implicit system: ActorSystem,
                                              fm: Materializer): Graph[FanOutShape2[RequestContext, ResponseContext, RawSlotEvent], Any] =
    FlowGraph.create() { implicit b ⇒
      import FlowGraph.Implicits._

      // TODO wouldn't be better to have them under a known parent? /user/SlotProcessor-0 seems weird
      val name = slotProcessorActorName.next()
      val slotProcessor = b.add {
        Flow.fromProcessor { () ⇒
          val actor = system.actorOf(Props(new SlotProcessor(slotIx, connectionFlow, settings)).withDeploy(Deploy.local),
            name)
          ActorProcessor[RequestContext, List[ProcessorOut]](actor)
        }.mapConcat(identity)
      }
      val split = b.add(Broadcast[ProcessorOut](2))

      slotProcessor.log("From-" + name) ~> split.in

      new FanOutShape2(slotProcessor.inlet,
        split.out(0).collect { case ResponseDelivery(r) ⇒ r }.outlet,
        split.out(1).collect { case r: RawSlotEvent ⇒ r }.outlet)
    }

  import ActorSubscriberMessage._
  import ActorPublisherMessage._

  //  private class SlotGraphStage(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
  //                                settings: ConnectionPoolSettings)(implicit fm: Materializer)
  //    extends GraphStage[FlowShape[RequestContext, List[ProcessorOut]]] {
  //
  //    val in = Inlet[RequestContext]("connectionSlot.in")
  //    val out = Outlet[List[ProcessorOut]]("connectionSlot.out")
  //    override val shape = FlowShape(in, out)
  //
  //    var inflightRequests = immutable.Queue.empty[RequestContext]
  //    val runnableGraph =
  //      Source.actorPublisher[HttpRequest](Props(new FlowInportActor(self)).withDeploy(Deploy.local))
  //      .via(connectionFlow)
  //      .toMat(Sink.actorSubscriber[HttpResponse](Props(new FlowOutportActor(self)).withDeploy(Deploy.local)))(Keep.both)
  //
  //
  //    def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
  //      var state = "unconnected"
  //      setHandler(in, new InHandler {
  //        override def onPush(): Unit = {
  //          val connectionInAndOut = runnableGraph.run()
  //          connectionInAndOut._2 ! Request(total)
  //        }
  //      })
  //    }
  //
  //  }

  /**
   * An actor mananging a series of materializations of the given `connectionFlow`.
   * To the outside it provides a stable flow stage, consuming `RequestContext` instances on its
   * input (ActorSubscriber) side and producing `List[ProcessorOut]` instances on its output
   * (ActorPublisher) side.
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any],
                              settings: ConnectionPoolSettings)(implicit fm: Materializer)
    extends ActorSubscriber with ActorPublisher[List[ProcessorOut]] with ActorLogging {

    import scala.concurrent.duration._
    var exposedPublisher: akka.stream.impl.ActorPublisher[Any] = _
    var inflightRequests = immutable.Queue.empty[RequestContext]
    val runnableGraph = Source.actorPublisher[HttpRequest](Props(new FlowInportActor(self)).withDeploy(Deploy.local))
      .log("beforeConnection")
      .via(connectionFlow.log("[[CONNECTION_FLOW]]"))
      .log("afterConnection")
      .toMat(Sink.actorSubscriber[HttpResponse](Props(new FlowOutportActor(self)).withDeploy(Deploy.local)))(Keep.both)
      .named("InternalConnectionFlow")

    override def requestStrategy = ZeroRequestStrategy
    override def receive = waitingExposedPublisher

    def waitingExposedPublisher: Receive = {
      case ExposedPublisher(publisher) ⇒
        exposedPublisher = publisher
        log.debug("become waitingForSubscribePending")
        context.become(waitingForSubscribePending)
      case other ⇒ throw new IllegalStateException(s"The first message must be `ExposedPublisher` but was [$other]")
    }

    def waitingForSubscribePending: Receive = {
      case SubscribePending ⇒
        exposedPublisher.takePendingSubscribers() foreach (s ⇒ self ! ActorPublisher.Internal.Subscribe(s))
        log.debug("become unconnected, from subscriber pending")
        context.become(unconnected)
    }

    val unconnected: Receive = {
      case m @ OnNext(rc: RequestContext) ⇒
        //        log.debug("unconnected got = " + m)
        //        log.debug("unconnected request context = " + rc)
        val (connInport, connOutport) = runnableGraph.run()
        connOutport ! Request(totalDemand)
        //        log.debug("become waitingForDownstreamDemandFromConnection")
        context.become(waitingForDemandFromConnection(connInport, connOutport, rc))

      case m @ Request(_) ⇒
        log.debug("unconnected got = " + m)
        if (remainingRequested == 0) request(1) // ask for first request if necessary

      case m @ Cancel ⇒
        log.debug("unconnected got = " + m)
        cancel()
        shutdown()
      case m @ OnComplete ⇒
        log.debug("unconnected got = " + m)
        onComplete()
      case m @ OnError(e) ⇒
        log.debug("unconnected got = " + m)
        onError(e)
      case c @ FromConnection(msg) ⇒
        log.debug("FROM CONNECTION = " + c)
        log.debug("from = " + sender())
        log.debug("totalDemand = " + totalDemand)
        log.debug("remainingRequested = " + this.remainingRequested)
        log.debug("canceled = " + this.canceled)
        log.debug("isActive = " + isActive)

      //        c match {
      //          case FromConnection(Request(n)) ⇒ if (remainingRequested == 0) request(n)
      //          case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError
      //          case FromConnection(OnComplete) ⇒ handleDisconnect(sender(), None)
      //          case FromConnection(OnError(e)) ⇒ handleDisconnect(sender(), Some(e))
      //          case FromConnection(OnNext(x))  ⇒ throw new IllegalStateException("Unexpected HttpResponse: " + x)
      //        }
    }

    def waitingForDemandFromConnection(connInport: ActorRef, connOutport: ActorRef,
                                       firstRequest: RequestContext): Receive = {
      case ev @ (Request(_) | Cancel)     ⇒ connOutport ! ev
      case ev @ (OnComplete | OnError(_)) ⇒ connInport ! ev
      case OnNext(x)                      ⇒ throw new IllegalStateException("Unrequested RequestContext: " + x)

      case FromConnection(Request(n)) ⇒
        log.debug("request from connection in waitingForDemandFromConnection() >>>>>")
        log.debug("from = " + sender())
        log.debug("totalDemand = " + totalDemand)
        log.debug("remainingRequested = " + remainingRequested)
        log.debug("canceled = " + canceled)
        log.debug("isActive = " + isActive)

        inflightRequests = inflightRequests.enqueue(firstRequest)
        log.info("inflightRequests = " + inflightRequests)
        request(n - remainingRequested)
        connInport ! OnNext(firstRequest.request)
        log.debug("become running")
        context.become(running(connInport, connOutport))

      case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError
      case FromConnection(OnComplete) ⇒ handleDisconnect(firstRequest, sender(), None)
      case FromConnection(OnError(e)) ⇒ handleDisconnect(firstRequest, sender(), Some(e)) // TODO BAD to COME_FROM here
      case FromConnection(OnNext(x))  ⇒ throw new IllegalStateException("Unexpected HttpResponse: " + x)
    }

    def running(connInport: ActorRef, connOutport: ActorRef): Receive = {
      case ev @ (Request(_) | Cancel)     ⇒ connOutport ! ev
      case ev @ (OnComplete | OnError(_)) ⇒ connInport ! ev
      case OnNext(rc: RequestContext) ⇒
        inflightRequests = inflightRequests.enqueue(rc)
        connInport ! OnNext(rc.request)

      case FromConnection(Request(n)) ⇒ request(n)
      case FromConnection(Cancel)     ⇒ if (!isActive) { cancel(); shutdown() } // else ignore and wait for accompanying OnComplete or OnError

      case FromConnection(OnNext(response: HttpResponse)) ⇒
        val requestContext = inflightRequests.head
        inflightRequests = inflightRequests.tail
        val (entity, whenCompleted) = response.entity match {
          case x: HttpEntity.Strict ⇒ x -> FastFuture.successful(())
          case x: HttpEntity.Default ⇒
            val (newData, whenCompleted) = StreamUtils.captureTermination(x.data)
            x.copy(data = newData) -> whenCompleted
          case x: HttpEntity.CloseDelimited ⇒
            val (newData, whenCompleted) = StreamUtils.captureTermination(x.data)
            x.copy(data = newData) -> whenCompleted
          case x: HttpEntity.Chunked ⇒
            val (newChunks, whenCompleted) = StreamUtils.captureTermination(x.chunks)
            x.copy(chunks = newChunks) -> whenCompleted
        }
        val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
        import fm.executionContext
        val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
        onNext(delivery :: requestCompleted :: Nil)

      case FromConnection(OnComplete) ⇒ handleDisconnect(null, sender(), None)
      case FromConnection(OnError(e)) ⇒ handleDisconnect(null, sender(), Some(e)) // TODO COME_FROM HERE IS OK
    }

    def handleDisconnect(r: RequestContext, connInport: ActorRef, error: Option[Throwable]): Unit = {
      log.info("total demand = " + totalDemand)
      log.debug("Slot {} disconnected after {}", slotIx, error getOrElse "regular connection close")

      val results: List[ProcessorOut] = {
        val res = inflightRequests.map { rc ⇒
          if (rc.retriesLeft == 0) {
            log.debug("retriesLeft = " + rc.retriesLeft)
            val reason = error.fold[Throwable](new RuntimeException("Unexpected disconnect"))(identityFunc)
            if (connInport ne null) {
              log.warning(s"Tearing down $connInport")
              connInport ! ActorPublisherMessage.Cancel
            }
            ResponseDelivery(ResponseContext(rc, Failure(reason)))
          } else SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1))
        }(collection.breakOut)

        if (res.isEmpty && error.isDefined) {
          log.warning("Empty response yet we should signal the failure!")
          ResponseDelivery(ResponseContext(r, Failure(new RuntimeException("Unexpected (early) disconnect", error.get)))) :: res.toList
        } else if (res.isEmpty) {
          log.warning("Empty response yet we should signal the failure!")
          ResponseDelivery(ResponseContext(r, Failure(new RuntimeException("Unexpected (early) disconnect")))) :: res.toList
        } else res.toList
      }
      inflightRequests = immutable.Queue.empty
      log.debug("results = " + (SlotEvent.Disconnected(slotIx, results.size) :: results))
      onNext(SlotEvent.Disconnected(slotIx, results.size) :: results)
      if (canceled) onComplete()

      COME_FROM("become unconnected")
      log.debug("become unconnected, because: " + error.map(_.getMessage))
      context.become(unconnected)
    }

    override def onComplete(): Unit = {
      exposedPublisher.shutdown(None)
      super.onComplete()
      shutdown()
    }

    override def onError(cause: Throwable): Unit = {
      exposedPublisher.shutdown(Some(cause))
      super.onError(cause)
      shutdown()
    }

    def shutdown(): Unit = {
      COME_FROM("shutting down")
      context.stop(self)
    }

    def COME_FROM(name: String, depth: Int = 5): Unit = {
      val t = new Throwable()
      val miniStack = t.getStackTrace.toList.take(depth).map("    " + _).mkString("\n")
      log.debug("== " + name + " == " + self.path.name + "\n" + miniStack)
    }

    override def postStop() = {
      super.postStop()
      println("!!!!! SLOT POST STOP !!!!! @ " + self)
    }

    override def preStart() = {
      super.preStart()
      println("!!!!! SLOT PRE START !!!!! @ " + self)
    }

  }

  private case class FromConnection(ev: Any) extends NoSerializationVerificationNeeded

  private class FlowInportActor(slotProcessor: ActorRef) extends ActorPublisher[HttpRequest] with ActorLogging {
    def receive: Receive = {
      case ev: Request ⇒
        log.debug("request = " + ev + ", slot:" + slotProcessor.path.name)
        slotProcessor ! FromConnection(ev)
      case Cancel ⇒
        log.debug("cancel then stop = " + Cancel + ", slot:" + slotProcessor.path.name)
        slotProcessor ! FromConnection(Cancel)
        context.stop(self)
      case OnNext(r: HttpRequest) ⇒
        log.debug("on next = " + r + ", slot:" + slotProcessor.path.name)
        onNext(r)
      case OnComplete ⇒
        log.debug("complete and then stop = " + ", slot:" + slotProcessor.path.name)

        onCompleteThenStop()
      case OnError(e) ⇒
        log.debug("error and then stop = " + e.getMessage + ", slot:" + slotProcessor.path.name)
        onErrorThenStop(e)
    }

    override def preStart = log.info("preStart of FlowInportActor self:" + self.path.name + ", slot:" + slotProcessor.path.name)
    override def postStop = log.info("postStop of FlowInportActor self:" + self.path.name + ", slot:" + slotProcessor.path.name)
  }

  private class FlowOutportActor(slotProcessor: ActorRef) extends ActorSubscriber with ActorLogging {
    def requestStrategy = ZeroRequestStrategy
    def receive: Receive = {
      case Request(n) ⇒
        log.debug(s"Requesting $n" + ", slot:" + slotProcessor.path.name)
        request(n)
      case Cancel ⇒
        log.debug("Cancelling..." + ", slot:" + slotProcessor.path.name)
        cancel()
      case ev: OnNext ⇒
        log.debug("OnNext = " + ev + ", slot:" + slotProcessor.path.name)
        slotProcessor ! FromConnection(ev)
      case ev @ (OnComplete | OnError(_)) ⇒
        log.debug("finishing = " + ev + "and then stop" + ", slot:" + slotProcessor.path.name)
        slotProcessor ! FromConnection(ev)
        context.stop(self)
    }
    override def preStart = log.info("preStart of FlowOutportActor self:" + self.path.name + ", slot:" + slotProcessor.path.name)
    override def postStop = log.info("postStop of FlowOutportActor self:" + self.path.name + ", slot:" + slotProcessor.path.name)
  }
}
