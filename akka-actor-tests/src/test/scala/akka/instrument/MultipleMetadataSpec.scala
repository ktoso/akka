/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ Props, Actor, ActorRef, ActorSystem }
import akka.dispatch.MessageDispatcher
import akka.testkit.{ ImplicitSender, TestKit }
import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

class MultipleMetadataSpec
  extends TestKit(ActorSystem("MultipleMetadataSpec", ConfigFactory.load(MultipleMetadataSpec.testConfig)))
  with WordSpecLike with Matchers with BeforeAndAfterAll with ImplicitSender {

  import MultipleMetadataSpec._

  override def afterAll(): Unit = shutdown()

  "Ensemble" should {

    "support multiple metadata accessors" in {
      val echo = system.actorOf(Props(new EchoActor), "echo")
      echo ! "hello"
      expectMsg(s"$Instrumentation1:${classOf[EchoActor].getName}:${Instrumentation1.toUpperCase}:hello")
      expectMsg(s"$Instrumentation2:${classOf[EchoActor].getName}:${Instrumentation2.toUpperCase}:hello")
      expectMsg("hello")
    }

  }
}

object MultipleMetadataSpec {

  val Instrumentation1 = "instrumentation1"
  val Instrumentation2 = "instrumentation2"

  val testConfig: Config = ConfigFactory.parseString(s"""
    akka.instrumentations += "${classOf[Instrumentation1].getName}"
    akka.instrumentations += "akka.instrument.NoActorInstrumentation"
    akka.instrumentations += "${classOf[Instrumentation2].getName}"
    """)

  class TestInstrumentation(name: String, metadata: ActorMetadata, dispatcherMetadata: DispatcherMetadata) extends EmptyActorInstrumentation {

    override def dispatcherStarted(dispatcher: MessageDispatcher, system: ActorSystem): Unit =
      dispatcherMetadata.attachTo(dispatcher, name.toUpperCase)

    override def actorCreated(actorRef: ActorRef, dispatcher: MessageDispatcher): Unit =
      metadata.attachTo(actorRef, createMetadata(actorRef, metadata.actorClass(actorRef), dispatcher))

    override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit =
      metadata.extractFrom(actorRef) match {
        case testMetadata: TestMetadata ⇒ sender ! testMetadata.received(message)
        case _                          ⇒
      }

    def createMetadata(actorRef: ActorRef, clazz: Class[_], dispatcher: MessageDispatcher): TestMetadata =
      if (clazz.getName startsWith classOf[MultipleMetadataSpec].getName) {
        new TestMetadata(s"$name:${clazz.getName}:${dispatcherMetadata.extractFrom(dispatcher)}")
      } else null
  }

  class Instrumentation1(metadata: ActorMetadata, dispatcherMetadata: DispatcherMetadata) extends TestInstrumentation(Instrumentation1, metadata, dispatcherMetadata)

  class Instrumentation2(metadata: ActorMetadata, dispatcherMetadata: DispatcherMetadata) extends TestInstrumentation(Instrumentation2, metadata, dispatcherMetadata)

  class TestMetadata(name: String) {
    def received(message: Any): String = s"$name:$message"
  }

  class EchoActor extends Actor {
    def receive = { case m ⇒ sender ! m }
  }

}
