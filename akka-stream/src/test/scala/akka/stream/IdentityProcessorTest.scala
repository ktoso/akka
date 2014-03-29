/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.api.Processor
import org.reactivestreams.tck.IdentityProcessorVerification
import akka.actor.Props
import akka.stream.impl.ActorProcessor
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.Ast
import akka.stream.testkit.TestProducer
import akka.testkit.TestEvent
import akka.testkit.EventFilter

class IdentityProcessorTest extends IdentityProcessorVerification[Int] with WithActorSystem with TestNGSuiteLike {

  system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))

  def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val fanoutSize = maxBufferSize / 2
    val inputSize = maxBufferSize - fanoutSize

    // FIXME can we use API to create the IdentityProcessor instead?
    def identityProps(settings: GeneratorSettings): Props =
      Props(new TransformProcessorImpl(settings, Ast.Transform(Unit, (_, in: Any) ⇒ (Unit, List(in)), (_: Any) ⇒ Nil)))

    val actor = system.actorOf(identityProps(
      GeneratorSettings(
        initialInputBufferSize = inputSize,
        maximumInputBufferSize = inputSize,
        initialFanOutBufferSize = fanoutSize,
        maxFanOutBufferSize = fanoutSize)))

    println(actor)

    ActorProcessor[Int, Int](actor)
  }

  def createHelperPublisher(elements: Int): Publisher[Int] = {
    import system.dispatcher
    val iter = Iterator from 1000
    TestProducer(if (elements > 0) iter take elements else iter).getPublisher
  }
}
