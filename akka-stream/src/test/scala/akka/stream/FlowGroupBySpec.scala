/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit._
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Flow
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowGroupBySpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  case class StreamPuppet(p: Publisher[Int]) {
    val probe = StreamTestKit.SubscriberProbe[Int]()
    p.subscribe(probe)
    val subscription = probe.expectSubscription()

    def request(demand: Int): Unit = subscription.request(demand)
    def expectNext(elem: Int): Unit = probe.expectNext(elem)
    def expectNoMsg(max: FiniteDuration): Unit = probe.expectNoMsg(max)
    def expectComplete(): Unit = probe.expectComplete()
    def expectError(e: Throwable) = probe.expectError(e)
    def cancel(): Unit = subscription.cancel()
  }

  class SubstreamsSupport(groupCount: Int = 2, elementCount: Int = 6) {
    val source = Flow((1 to elementCount).iterator).toPublisher(materializer)
    val groupStream = Flow(source).groupBy(_ % groupCount).toPublisher(materializer)
    val masterConsumer = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()

    groupStream.subscribe(masterConsumer)
    val masterSubscription = masterConsumer.expectSubscription()

    def getSubproducer(expectedKey: Int): Publisher[Int] = {
      masterSubscription.request(1)
      expectSubproducer(expectedKey: Int)
    }

    def expectSubproducer(expectedKey: Int): Publisher[Int] = {
      val (key, substream) = masterConsumer.expectNext()
      key should be(expectedKey)
      substream
    }

  }

  case class TE(message: String) extends RuntimeException(message) with NoStackTrace

  "groupBy" must {
    "work in the happy case" in new SubstreamsSupport(groupCount = 2) {
      val s1 = StreamPuppet(getSubproducer(1))
      masterConsumer.expectNoMsg(100.millis)

      s1.expectNoMsg(100.millis)
      s1.request(1)
      s1.expectNext(1)
      s1.expectNoMsg(100.millis)

      val s2 = StreamPuppet(getSubproducer(0))

      s2.expectNoMsg(100.millis)
      s2.request(2)
      s2.expectNext(2)
      s2.expectNext(4)

      s2.expectNoMsg(100.millis)

      s1.request(1)
      s1.expectNext(3)

      s2.request(1)
      s2.expectNext(6)
      s2.expectComplete()

      s1.request(1)
      s1.expectNext(5)
      s1.expectComplete()

      masterConsumer.expectComplete()

    }

    "accept cancellation of substreams" in new SubstreamsSupport(groupCount = 2) {
      StreamPuppet(getSubproducer(1)).cancel()

      val substream = StreamPuppet(getSubproducer(0))
      substream.request(2)
      substream.expectNext(2)
      substream.expectNext(4)
      substream.expectNoMsg(100.millis)

      substream.request(2)
      substream.expectNext(6)
      substream.expectComplete()

      masterConsumer.expectComplete()

    }

    "accept cancellation of master stream when not consumed anything" in {
      val producerProbe = StreamTestKit.PublisherProbe[Int]()
      val producer = Flow(producerProbe).groupBy(_ % 2).toPublisher(materializer)
      val consumer = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      producer.subscribe(consumer)

      val upstreamSubscription = producerProbe.expectSubscription()
      val downstreamSubscription = consumer.expectSubscription()
      downstreamSubscription.cancel()
      upstreamSubscription.expectCancellation()
    }

    "accept cancellation of master stream when substreams are open" in new SubstreamsSupport(groupCount = 3, elementCount = 13) {
      pending
      // FIXME: Needs handling of loose substreams that no one refers to anymore.
      //      val substream = StreamPuppet(getSubproducer(1))
      //
      //      substream.request(1)
      //      substream.expectNext(1)
      //
      //      masterSubscription.cancel()
      //      masterConsumer.expectNoMsg(100.millis)
      //
      //      // Open substreams still work, others are discarded
      //      substream.request(4)
      //      substream.expectNext(4)
      //      substream.expectNext(7)
      //      substream.expectNext(10)
      //      substream.expectNext(13)
      //      substream.expectComplete()
    }

    "work with fanout on substreams" in new SubstreamsSupport(groupCount = 2) {
      val substreamProducer = getSubproducer(1)
      getSubproducer(0)

      val substreamConsumer1 = StreamPuppet(substreamProducer)
      val substreamConsumer2 = StreamPuppet(substreamProducer)

      substreamConsumer1.request(1)
      substreamConsumer1.expectNext(1)
      substreamConsumer2.request(1)
      substreamConsumer2.expectNext(1)

      substreamConsumer1.request(1)
      substreamConsumer1.expectNext(3)
      substreamConsumer2.request(1)
      substreamConsumer2.expectNext(3)
    }

    "work with fanout on master stream" in {
      val source = Flow((1 to 4).iterator).toPublisher(materializer)
      val groupStream = Flow(source).groupBy(_ % 2).toPublisher(materializer)
      val masterConsumer1 = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      val masterConsumer2 = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()

      groupStream.subscribe(masterConsumer1)
      groupStream.subscribe(masterConsumer2)

      val masterSubscription1 = masterConsumer1.expectSubscription()
      val masterSubscription2 = masterConsumer2.expectSubscription()

      masterSubscription1.request(2)
      masterSubscription2.request(1)

      val (key11, substream11) = masterConsumer1.expectNext()
      key11 should be(1)
      val (key21, substream21) = masterConsumer2.expectNext()
      key21 should be(1)

      val puppet11 = StreamPuppet(substream11)
      val puppet21 = StreamPuppet(substream21)

      puppet11.request(2)
      puppet11.expectNext(1)
      puppet11.expectNext(3)

      puppet21.request(1)
      puppet21.expectNext(1)
      puppet21.cancel()

      masterSubscription2.cancel()

      val (key12, substream12) = masterConsumer1.expectNext()
      key12 should be(0)

      val puppet12 = StreamPuppet(substream12)
      puppet12.request(1)
      puppet12.expectNext(2)
      puppet12.cancel()
      masterSubscription1.cancel()
    }

    "work with empty input stream" in {
      val producer = Flow(List.empty[Int]).groupBy(_ % 2).toPublisher(materializer)
      val consumer = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      producer.subscribe(consumer)

      consumer.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "abort on onError from upstream" in {
      val producerProbe = StreamTestKit.PublisherProbe[Int]()
      val producer = Flow(producerProbe).groupBy(_ % 2).toPublisher(materializer)
      val consumer = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      producer.subscribe(consumer)

      val upstreamSubscription = producerProbe.expectSubscription()

      val downstreamSubscription = consumer.expectSubscription()
      downstreamSubscription.request(100)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      consumer.expectError(e)
    }

    "abort on onError from upstream when substreams are running" in {
      val producerProbe = StreamTestKit.PublisherProbe[Int]()
      val producer = Flow(producerProbe).groupBy(_ % 2).toPublisher(materializer)
      val consumer = StreamTestKit.SubscriberProbe[(Int, Publisher[Int])]()
      producer.subscribe(consumer)

      val upstreamSubscription = producerProbe.expectSubscription()

      val downstreamSubscription = consumer.expectSubscription()
      downstreamSubscription.request(100)

      upstreamSubscription.sendNext(1)

      val (_, substream) = consumer.expectNext()
      val substreamPuppet = StreamPuppet(substream)

      substreamPuppet.request(1)
      substreamPuppet.expectNext(1)

      val e = TE("test")
      upstreamSubscription.sendError(e)

      substreamPuppet.expectError(e)
      consumer.expectError(e)

    }
  }

}
