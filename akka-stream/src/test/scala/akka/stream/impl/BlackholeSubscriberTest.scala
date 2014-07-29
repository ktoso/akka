/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.{ MaterializerSettings, FlowMaterializer, Timeouts, WithActorSystem }
import org.reactivestreams.tck.SubscriberVerification.SubscriberProbe
import org.reactivestreams.tck.{ SubscriberVerification, TestEnvironment }
import org.reactivestreams.{ Publisher, Subscriber }
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

  def createPublisher(elements: Int): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements
    Flow(iterable).toPublisher(materializer)
  }

  override def createSubscriber(probe: SubscriberProbe[Int]): Subscriber[Int] =
    new BlackholeSubscriber[Int](2)

  override def createHelperPublisher(elements: Int): Publisher[Int] =
    createPublisher(elements)

}
