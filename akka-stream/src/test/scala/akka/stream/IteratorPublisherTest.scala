/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike
import akka.stream.scaladsl.Flow
import akka.actor.ActorSystem
import akka.stream.testkit.AkkaSpec

class IteratorPublisherTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IteratorPublisherTest].getSimpleName, AkkaSpec.testConf))
  }

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512, dispatcher = "akka.test.stream-dispatcher"))(system)

  def createPublisher(elements: Long): Publisher[Int] = {
    val iter: Iterator[Int] =
      if (elements == Long.MaxValue)
        Iterator from 0
      else
        (Iterator from 0).take(elements.toInt)

    Flow(iter).toPublisher(materializer)
    //    Flow(iter).map { i ⇒ println(s">>> $i"); i }.toPublisher(materializer)
  }

  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests
}