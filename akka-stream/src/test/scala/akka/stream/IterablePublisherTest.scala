/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams._
import org.reactivestreams.tck.{ TestEnvironment, PublisherVerification }
import scala.collection.immutable
import akka.stream.scaladsl.Flow
import akka.actor.ActorSystem
import akka.stream.testkit.AkkaSpec

class IterablePublisherTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IterablePublisherTest].getSimpleName, AkkaSpec.testConf))
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

  override def createCompletedStatePublisher(): Publisher[Int] =
    Flow[Int](Nil).toPublisher(materializer)

  override def createErrorStatePublisher(): Publisher[Int] =
    new Publisher[Int] {
      override def subscribe(s: Subscriber[Int]): Unit = {
        s.onError(new Exception("Unable to serve subscribers right now!"))
      }
    }

}