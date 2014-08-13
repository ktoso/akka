/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import org.reactivestreams._
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike

import scala.collection.immutable

class IterableOnePublisherTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IterableOnePublisherTest].getSimpleName, AkkaSpec.testConf))
  }

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512, dispatcher = "akka.test.stream-dispatcher"))(system)

  def createPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] = 0 until 1

    Flow(iterable).toPublisher(materializer)
  }

  override def maxElementsFromPublisher = 1

  override def createCompletedStatePublisher(): Publisher[Int] =
    Flow[Int](Nil).toPublisher(materializer)

  override def createErrorStatePublisher(): Publisher[Int] =
    new Publisher[Int] {
      override def subscribe(s: Subscriber[Int]): Unit = {
        s.onError(new Exception("Unable to serve subscribers right now!"))
      }
    }

}