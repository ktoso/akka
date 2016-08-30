/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.impl.engine.client.PoolConductor.{ ConnectEagerlyCommand, DispatchCommand, SlotCommand }
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, HttpResponse }
import akka.stream.impl.{ ActorMaterializerImpl, ConstantFun }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.{ Graph, Materializer }

import scala.collection.immutable
import scala.concurrent.Future
import scala.language.existentials
import scala.util.{ Failure, Success }

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
    /**
     * Slot with id "slotIx" has responded to request from PoolConductor and connected immediately
     * Ordinary connections from slots don't produce this event
     */
    final case class ConnectedEagerly(slotIx: Int) extends SlotEvent
  }

  def apply(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any])(
    implicit
    m: Materializer): Graph[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent], Any] = {
    val log = ActorMaterializerHelper.downcast(m).logger
    new SlotProcessor(slotIx, connectionFlow, log)
  }

  /**
   * An actor managing a series of materializations of the given `connectionFlow`.
   *
   * To the outside it provides a stable flow stage, consuming `SlotCommand` instances on its internal connection flows.
   *
   * The given `connectionFlow` is materialized into a running flow whenever required.
   * Completion and errors from the connection are not surfaced to the outside (unless we are
   * shutting down completely).
   */
  private class SlotProcessor(slotIx: Int, connectionFlow: Flow[HttpRequest, HttpResponse, Any], log: LoggingAdapter)(implicit mat: Materializer)
    extends GraphStage[FanOutShape2[SlotCommand, ResponseContext, RawSlotEvent]] {

    val commandsIn: Inlet[SlotCommand] = Inlet("SlotProcessor.commandsIn")
    val responsesOut: Outlet[ResponseContext] = Outlet("SlotProcessor.responseOut")
    val eventsOut: Outlet[RawSlotEvent] = Outlet("SlotProcessor.eventOut")

    override val shape = new FanOutShape2(commandsIn, responsesOut, eventsOut)

    override def createLogic(commandsInheritedAttributes: Attributes): GraphStageLogic =
      new TimerGraphStageLogic(shape) with InHandler /* with OutHandler */ { unconnected ⇒
        private val inflightRequests = new java.util.ArrayDeque[RequestContext](4)

        private var connectionOutlet: SubSourceOutlet[HttpRequest] = _
        private var connectionInlet: SubSinkInlet[HttpResponse] = _

        private var firstRequest: RequestContext = _

        // unconnected
        override def onPush(): Unit = grab(commandsIn) match {
          case ConnectEagerlyCommand ⇒
            log.debug("BERN-{}: PoolSlot: onPush when unconnected", slotIx)
            runConnect()

          case DispatchCommand(rc: RequestContext) ⇒
            log.debug("BERN-{}: PoolSlot: onPush({}) when unconnected", slotIx, rc)
            firstRequest = rc

            runConnect()
        }

        private def connectionOutFlowHandler = new OutHandler {
          // connectionOutlet is ready for an element, we can send a HttpRequest to the subflow
          override def onPull(): Unit = {
            log.debug("BERN-{}: connectionFlow: onPull, first {} inflight {}", slotIx, firstRequest, inflightRequests)

            // give the connectionFlow a HttpRequest
            if (firstRequest ne null) {
              inflightRequests.add(firstRequest)
              connectionOutlet.push(firstRequest.request)
              firstRequest = null
            } else if (isAvailable(commandsIn))
              grab(commandsIn) match {
                case DispatchCommand(rc) ⇒
                  inflightRequests.add(rc)
                  connectionOutlet.push(rc.request)
                case x ⇒
                  log.error("invalid command {}", x)
              }
            if (!hasBeenPulled(commandsIn)) pull(commandsIn)
          }

          // connectionOutlet has been closed (IgnoreTerminateOutput)
          override def onDownstreamFinish(): Unit = {
            log.debug("BERN-{}: onDownstreamFinish first {} inflight {}!!", slotIx, firstRequest, inflightRequests)

            if (firstRequest == null && inflightRequests.isEmpty) {
              push(eventsOut, SlotEvent.Disconnected(slotIx, 0))

              connectionOutlet.complete()
              setHandler(commandsIn, unconnected)

              if (!hasBeenPulled(commandsIn)) pull(commandsIn)
            }

            //            if (!inflightRequests.isEmpty) {
            //              import scala.collection.JavaConverters._
            //              log.info("BERN-{}: onDownstreamFinish RECONNECTING since pending requests {}...", slotIx, inflightRequests.asScala.map(_.request.uri))
            //              runConnect()
            //            }
          }
        }

        private def connectionInFlowHandler = new InHandler {

          // a new element is available on connectionInlet Inlet - that is a HttpResponse is being returned
          override def onPush(): Unit = {
            log.debug("BERN-{}: connectionFlow: onPush", slotIx)
            // consume a HttpResponse from the connectonFlow

            val response: HttpResponse = connectionInlet.grab()

            log.debug("BERN-{}: connectionFlow: onPush {} {}", slotIx, response)
            val requestContext = inflightRequests.pop()

            val (entity, whenCompleted) = HttpEntity.captureTermination(response.entity)
            val delivery = ResponseDelivery(ResponseContext(requestContext, Success(response withEntity entity)))
            import mat.executionContext
            val requestCompleted = SlotEvent.RequestCompletedFuture(whenCompleted.map(_ ⇒ SlotEvent.RequestCompleted(slotIx)))
            push(responsesOut, delivery.response)
            push(eventsOut, requestCompleted)

            connectionInlet.pull()
          }

          // this happens if we closed the source 
          override def onUpstreamFinish(): Unit = {
            log.debug("BERN-{}: onUpstreamFinish", slotIx)
            // TODO WHAT THEN?

            runConnect()
          }

          // a Failure[HttpResponse] is coming back instead
          override def onUpstreamFailure(ex: Throwable): Unit = {
            log.error(ex, "BERN-{}: onUpstreamFailure first {} inflight {}", slotIx, firstRequest, inflightRequests)
            if (firstRequest ne null) {
              val ctx = ResponseContext(firstRequest, Failure(new UnexpectedDisconnectException("Unexpected (early) disconnect", ex)))
              emit(responsesOut, ctx, () ⇒ log.debug("Early disconnect failure"))
            } else {
              import scala.collection.JavaConverters._
              inflightRequests.iterator().asScala.foreach { rc ⇒
                if (rc.retriesLeft == 0) emit(responsesOut, ResponseContext(rc, Failure(ex)), () ⇒ log.debug("Failure sent"))
                else emit(eventsOut, SlotEvent.RetryRequest(rc.copy(retriesLeft = rc.retriesLeft - 1)), () ⇒ log.debug("Retry sent"))
              }
            }
            emit(eventsOut, SlotEvent.Disconnected(slotIx, inflightRequests.size), () ⇒ log.debug("Disconnected sent"))
            firstRequest = null
            inflightRequests.clear()

            connectionOutlet.complete()
            setHandler(commandsIn, unconnected)
          }
        }

        private def connected = new InHandler {
          override def onPush(): Unit = {
            log.debug("BERN-{}: PoolSlot: onPush when connected", slotIx)
            if (connectionOutlet.isAvailable) {
              grab(commandsIn) match {
                case DispatchCommand(rc: RequestContext) ⇒
                  inflightRequests.add(rc)
                  connectionOutlet.push(rc.request)
                case x ⇒
                  log.error("invalid command {}", x)
              }
              pull(commandsIn)
            } else if (!connectionInlet.hasBeenPulled) {
              log.warning("connectionInlet.isClosed = " + connectionInlet.isClosed)
              if (connectionInlet.isClosed) runConnect()
              else connectionInlet.pull()
            }
          }
        }

        override def preStart(): Unit = {
          // request first request/command
          pull(commandsIn)

          //          import scala.concurrent.duration._
          //          scheduleOnce("DUMP", 15.seconds)
        }

        setHandler(commandsIn, this)

        setHandler(responsesOut, new OutHandler {
          override def onPull(): Unit = {
            log.debug("BERN-{}: PoolSlot: onPull(responsesOut)", slotIx)
          }
        })
        setHandler(eventsOut, new OutHandler {
          override def onPull(): Unit = log.debug("BERN-{}: PoolSlot: onPull(eventsOut)", slotIx)
        })

        override protected def onTimer(timerKey: Any): Unit = {
          interpreter.dumpWaits()
        }

        /** Materialize new connection and set up the apropriate SubSink/Sources */
        private def runConnect(): Unit = {
          log.info("RECONNECTING...")
          connectionOutlet = new SubSourceOutlet[HttpRequest]("RequestSubSource")
          connectionOutlet.setHandler(connectionOutFlowHandler)

          connectionInlet = new SubSinkInlet[HttpResponse]("ResponseSubSink")
          connectionInlet.setHandler(connectionInFlowHandler)

          setHandler(commandsIn, connected)

          Source.fromGraph(connectionOutlet.source).via(connectionFlow).runWith(Sink.fromGraph(connectionInlet.sink))(subFusingMaterializer)

          connectionInlet.pull()
        }

      }

  }

  final class UnexpectedDisconnectException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) {
    def this(msg: String) = this(msg, null)
  }
}
