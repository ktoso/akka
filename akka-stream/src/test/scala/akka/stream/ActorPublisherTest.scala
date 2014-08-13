/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem

class ActorPublisherTest(_system: ActorSystem, /*env: TestEnvironment,*/ publisherShutdownTimeout: Long) {
  // FIXME: Needs new TCK version

  //  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  //  with WithActorSystem with TestNGSuiteLike {
  //
  //  implicit val system = _system
  //  import system.dispatcher
  //
  //  def this(system: ActorSystem) {
  //    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  //  }
  //
  //  def this() {
  //    this(ActorSystem(classOf[ActorPublisherTest].getSimpleName, AkkaSpec.testConf))
  //  }
  //
  //  private val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))
  //
  //  private def createProducer(elements: Long): Producer[Int] = {
  //    val iter = Iterator from 1000
  //    val iter2 = if (elements > 0) iter take elements else iter
  //    Flow(() ⇒ if (iter2.hasNext) iter2.next() else throw Stop).toProducer(materializer)
  //  }
  //
  //  def createPublisher(elements: Int): Publisher[Int] = createProducer(elements).getPublisher
  //
  //  override def createCompletedStatePublisher(): Publisher[Int] = {
  //    val pub = createProducer(1)
  //    Flow(pub).consume(materializer)
  //    Thread.sleep(100)
  //    pub.getPublisher
  //  }
  //
  //  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests
}