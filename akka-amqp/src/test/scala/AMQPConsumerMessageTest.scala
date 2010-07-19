/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.amqp._
import org.multiverse.api.latches.StandardLatch
import se.scalablesolutions.akka.actor.Actor._
import org.scalatest.matchers.MustMatchers
import java.util.concurrent.{CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.amqp.AMQP.{ExchangeParameters, ConsumerParameters, ChannelParameters, ProducerParameters}

class AMQPConsumerMessageTest extends JUnitSuite with MustMatchers with Logging {

  @Test
  def consumerMessage = if (AMQPTest.enabled) {
    val connection = AMQP.newConnection()
    try {

      val countDown = new CountDownLatch(2)
      val channelCallback = actor {
        case Started => countDown.countDown
        case Restarting => ()
        case Stopped => ()
      }

      val exchangeParameters = ExchangeParameters("text_exchange",ExchangeType.Direct)
      val channelParameters = ChannelParameters(channelCallback = Some(channelCallback))

      val payloadLatch = new StandardLatch
      val consumer = AMQP.newConsumer(connection, ConsumerParameters(exchangeParameters, "non.interesting.routing.key", actor {
        case Delivery(payload, _, _, _, _) => payloadLatch.open
      }, channelParameters = Some(channelParameters)))

      val producer = AMQP.newProducer(connection,
        ProducerParameters(exchangeParameters, channelParameters = Some(channelParameters)))
      
      countDown.await(2, TimeUnit.SECONDS) must be (true)
      producer ! Message("some_payload".getBytes, "non.interesting.routing.key")
      payloadLatch.tryAwait(2, TimeUnit.SECONDS) must be (true)
    } finally {
      connection.stop
    }
  }

  @Test
  def dummy {
    // amqp tests need local rabbitmq server running, so a disabled by default.
    // this dummy test makes sure that the whole test class doesn't fail because of missing tests
    assert(true)
  }
}