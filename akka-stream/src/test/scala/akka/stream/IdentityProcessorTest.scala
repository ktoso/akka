/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.stream.impl.{ ActorBasedFlowMaterializer, Ast }
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.tck.{ IdentityProcessorVerification, TestEnvironment }
import org.reactivestreams.{ Processor, Publisher }
import org.scalatest.testng.TestNGSuiteLike

class IdentityProcessorTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends IdentityProcessorVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IdentityProcessorTest].getSimpleName, AkkaSpec.testConf))
  }

  val processorCounter = new AtomicInteger

  def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val fanoutSize = maxBufferSize / 2
    val inputSize = maxBufferSize - fanoutSize

    val materializer = new ActorBasedFlowMaterializer(
      MaterializerSettings(
        initialInputBufferSize = inputSize,
        maximumInputBufferSize = inputSize,
        initialFanOutBufferSize = fanoutSize,
        maxFanOutBufferSize = fanoutSize,
        dispatcher = "akka.test.stream-dispatcher"),
      system, system.name)

    val processor = materializer.processorForNode(Ast.Transform(
      new Transformer[Any, Any] {
        override def onNext(in: Any) = List(in)
      }), "IdentityProcessorTest-" + processorCounter.incrementAndGet(), 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  def createHelperPublisher(elements: Long): Publisher[Int] = {
    val materializer = FlowMaterializer(MaterializerSettings(
      maximumInputBufferSize = 512, dispatcher = "akka.test.stream-dispatcher"))(system)
    val iter = Iterator from 1000
    Flow(if (elements == Long.MaxValue) iter else iter take elements.toInt).toPublisher(materializer)
  }

  override def maxElementsFromPublisher(): Long = 1000

  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests
  override def createCompletedStatePublisher(): Publisher[Int] = null // ignore completed-state tests
}
