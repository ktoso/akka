/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import org.reactivestreams.Publisher

import scala.util.control.NoStackTrace

abstract class TwoStreamsSetup extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  val TestException = TE("test")

  type Outputs

  def operationUnderTest(in1: Flow[Int], in2: Publisher[Int]): Flow[Outputs]

  def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val consumer = StreamTestKit.SubscriberProbe[Outputs]()
    operationUnderTest(Flow(p1), p2).toPublisher(materializer).subscribe(consumer)
    consumer
  }

  def failedPublisher[T]: Publisher[T] = StreamTestKit.errorPublisher[T](TestException)

  def completedPublisher[T]: Publisher[T] = StreamTestKit.emptyPublisher[T]

  def nonemptyPublisher[T](elems: Iterator[T]): Publisher[T] = Flow(elems).toPublisher(materializer)

  def soonToFailPublisher[T]: Publisher[T] = StreamTestKit.lazyErrorPublisher[T](TestException)

  def soonToCompletePublisher[T]: Publisher[T] = StreamTestKit.lazyEmptyPublisher[T]

  def commonTests() = {
    "work with two immediately completed producers" in {
      val consumer = setup(completedPublisher, completedPublisher)
      consumer.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two delayed completed producers" in {
      val consumer = setup(soonToCompletePublisher, soonToCompletePublisher)
      consumer.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one immediately completed and one delayed completed producer" in {
      val consumer = setup(completedPublisher, soonToCompletePublisher)
      consumer.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two immediately failed producers" in {
      val consumer = setup(failedPublisher, failedPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with two delayed failed producers" in {
      val consumer = setup(soonToFailPublisher, soonToFailPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    // Warning: The two test cases below are somewhat implementation specific and might fail if the implementation
    // is changed. They are here to be an early warning though.
    "work with one immediately failed and one delayed failed producer (case 1)" in {
      val consumer = setup(soonToFailPublisher, failedPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one immediately failed and one delayed failed producer (case 2)" in {
      val consumer = setup(failedPublisher, soonToFailPublisher)
      consumer.expectErrorOrSubscriptionFollowedByError(TestException)
    }
  }

}
