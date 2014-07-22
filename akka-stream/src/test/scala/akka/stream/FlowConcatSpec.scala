/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow
import org.reactivestreams.Publisher
import scala.concurrent.Promise

class FlowConcatSpec extends TwoStreamsSetup {

  type Outputs = Int
  override def operationUnderTest(in1: Flow[Int], in2: Publisher[Int]) = in1.concat(in2)

  "Concat" must {

    "work in the happy case" in {
      val source0 = Flow(List.empty[Int].iterator).toPublisher(materializer)
      val source1 = Flow((1 to 4).iterator).toPublisher(materializer)
      val source2 = Flow((5 to 10).iterator).toPublisher(materializer)
      val p = Flow(source0).concat(source1).concat(source2).toPublisher(materializer)

      val probe = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(probe)
      val subscription = probe.expectSubscription()

      for (i ← 1 to 10) {
        subscription.request(1)
        probe.expectNext(i)
      }

      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty producer" in {
      val consumer1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.request(5)
      consumer1.expectNext(1)
      consumer1.expectNext(2)
      consumer1.expectNext(3)
      consumer1.expectNext(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.request(5)
      consumer2.expectNext(1)
      consumer2.expectNext(2)
      consumer2.expectNext(3)
      consumer2.expectNext(4)
      consumer2.expectComplete()
    }

    "work with one delayed completed and one nonempty producer" in {
      val consumer1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.request(5)
      consumer1.expectNext(1)
      consumer1.expectNext(2)
      consumer1.expectNext(3)
      consumer1.expectNext(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.request(5)
      consumer2.expectNext(1)
      consumer2.expectNext(2)
      consumer2.expectNext(3)
      consumer2.expectNext(4)
      consumer2.expectComplete()
    }

    "work with one immediately failed and one nonempty producer" in {
      val consumer1 = setup(failedPublisher, nonemptyPublisher((1 to 4).iterator))
      consumer1.expectErrorOrSubscriptionFollowedByError(TestException)

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), failedPublisher)
      consumer2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one delayed failed and one nonempty producer" in {
      val consumer1 = setup(soonToFailPublisher, nonemptyPublisher((1 to 4).iterator))
      consumer1.expectErrorOrSubscriptionFollowedByError(TestException)

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), soonToFailPublisher)
      consumer2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "correctly handle async errors in secondary upstream" in {
      val promise = Promise[Int]()
      val flow = Flow(List(1, 2, 3)).concat(Flow(promise.future).toPublisher(materializer))
      val consumer = StreamTestKit.SubscriberProbe[Int]()
      flow.produceTo(materializer, consumer)
      val subscription = consumer.expectSubscription()
      subscription.request(4)
      consumer.expectNext(1)
      consumer.expectNext(2)
      consumer.expectNext(3)
      promise.failure(TestException)
      consumer.expectError(TestException)
    }
  }
}
