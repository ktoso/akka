/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.{ FlowMaterializer, MaterializerSettings, Timeouts, WithActorSystem }
import org.reactivestreams.tck.{ SubscriberBlackboxVerification, TestEnvironment }
import org.reactivestreams.{ Publisher, Subscriber }
import org.scalatest.testng.TestNGSuiteLike

import scala.collection.immutable

class BlackholeSubscriberTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends SubscriberBlackboxVerification[Int](env)
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

  override def createSubscriber(): Subscriber[Int] =
    new BlackholeSubscriber[Int](2)

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == Long.MaxValue) 1 to Int.MaxValue
      else 0 until elements.toInt

    println("elements = " + elements)

    Flow(iterable).toPublisher(materializer)
  }

  def infinite: Stream[Int] = Stream.continually(List(1, 2, 3, 4, 5).toStream).flatten

}
