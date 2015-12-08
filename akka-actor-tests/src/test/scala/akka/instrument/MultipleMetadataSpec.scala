/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ Props, Actor, ActorRef, ActorSystem }
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
      expectMsg(s"$Instrumentation1:${classOf[EchoActor].getName}:hello")
      expectMsg(s"$Instrumentation2:${classOf[EchoActor].getName}:hello")
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

  class TestInstrumentation(metadataAccessor: MetadataAccessor[TestMetadata]) extends EmptyActorInstrumentation {
    override def actorCreated(actorRef: ActorRef): Unit =
      metadataAccessor.attachTo(actorRef)

    override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
      val metadata = metadataAccessor.extractFrom(actorRef)
      if (metadata ne null) sender ! metadata.received(message)
      ActorInstrumentation.EmptyContext
    }
  }

  class Instrumentation1(metadataAccessor: MetadataAccessor[TestMetadata]) extends TestInstrumentation(metadataAccessor) {
    def this(metadataAccessorFactory: MetadataAccessor.Factory) =
      this(metadataAccessorFactory.create(new TestMetadataAccessor(Instrumentation1)))
  }

  class Instrumentation2(metadataAccessor: MetadataAccessor[TestMetadata]) extends TestInstrumentation(metadataAccessor) {
    def this(metadataAccessorFactory: MetadataAccessor.Factory) =
      this(metadataAccessorFactory.create(new TestMetadataAccessor(Instrumentation2)))
  }

  class TestMetadata(name: String) {
    def received(message: Any): String = s"$name:$message"
  }

  class TestMetadataAccessor(name: String) extends MetadataAccessor[TestMetadata] {
    override def createMetadata(actorRef: ActorRef, clazz: Class[_]): TestMetadata = {
      if (clazz.getName startsWith classOf[MultipleMetadataSpec].getName) {
        new TestMetadata(s"$name:${clazz.getName}")
      } else null
    }

    override def extractMetadata(metadata: AnyRef): TestMetadata = metadata match {
      case testMetadata: TestMetadata ⇒ testMetadata
      case _                          ⇒ null
    }
  }

  class EchoActor extends Actor {
    def receive = { case m ⇒ sender ! m }
  }

}
