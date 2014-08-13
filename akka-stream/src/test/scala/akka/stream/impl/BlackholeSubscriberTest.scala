/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.{ MaterializerSettings, FlowMaterializer, Timeouts, WithActorSystem }
import org.reactivestreams.tck.SubscriberVerification.{ SubscriberPuppet, SubscriberProbe }
import org.reactivestreams.tck.{ SubscriberVerification, TestEnvironment }
import org.reactivestreams.{ Subscription, Publisher, Subscriber }
import org.scalatest.testng.TestNGSuiteLike

import scala.collection.immutable

class BlackholeSubscriberTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends SubscriberVerification[Int](env)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[BlackholeSubscriberTest].getSimpleName, AkkaSpec.testConf))
  }

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512, dispatcher = "akka.test.stream-dispatcher"))(system)

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements.toInt
    Flow(iterable).toPublisher(materializer)
  }

  override def createSubscriber(probe: SubscriberProbe[Int]): Subscriber[Int] =
    new BlackholeSubscriber[Int](2) {
      override def onSubscribe(sub: Subscription): Unit = {
        super.onSubscribe(sub)
        probe.registerOnSubscribe(new SubscriberPuppet {
          override def triggerRequest(elements: Long): Unit = sub.request(elements)

          override def signalCancel(): Unit = sub.cancel()
        })
      }

      override def onError(cause: Throwable): Unit = {
        super.onError(cause)
        probe.registerOnError(cause)
      }

      override def onComplete(): Unit = {
        super.onComplete()
        probe.registerOnComplete()
      }

      override def onNext(element: Int): Unit = {
        super.onNext(element)
        probe.registerOnNext(element)
      }
    }

  override def createHelperPublisher(elements: Long): Publisher[Int] =
    createPublisher(elements)

}
