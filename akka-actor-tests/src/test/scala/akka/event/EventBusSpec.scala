/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import language.postfixOps

import org.scalatest.BeforeAndAfterEach
import akka.testkit._
import scala.concurrent.duration._
import java.util.concurrent.atomic._
import akka.actor.{ Props, Actor, ActorRef, ActorSystem }
import java.util.Comparator
import akka.japi.{ Procedure, Function }

object EventBusSpec {
  class TestActorWrapperActor(testActor: ActorRef) extends Actor {
    def receive = {
      case x ⇒ testActor forward x
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class EventBusSpec(val busName: String) extends AkkaSpec with BeforeAndAfterEach {
  import EventBusSpec._
  type BusType <: EventBus

  def createNewEventBus(): BusType

  def createEvents(numberOfEvents: Int): Iterable[BusType#Event]

  def createSubscriber(pipeTo: ActorRef): BusType#Subscriber

  def classifierFor(event: BusType#Event): BusType#Classifier

  def disposeSubscriber(system: ActorSystem, subscriber: BusType#Subscriber): Unit

  busName must {

    def createNewSubscriber() = createSubscriber(testActor).asInstanceOf[bus.Subscriber]
    def getClassifierFor(event: BusType#Event) = classifierFor(event).asInstanceOf[bus.Classifier]
    def createNewEvents(numberOfEvents: Int): Iterable[bus.Event] = createEvents(numberOfEvents).asInstanceOf[Iterable[bus.Event]]

    val bus = createNewEventBus()
    val events = createNewEvents(100)
    val event = events.head
    val classifier = getClassifierFor(event)
    val subscriber = createNewSubscriber()

    "allow subscribers" in {
      bus.subscribe(subscriber, classifier) should be(true)
    }

    "allow to unsubscribe already existing subscriber" in {
      bus.unsubscribe(subscriber, classifier) should be(true)
    }

    "not allow to unsubscribe non-existing subscriber" in {
      val sub = createNewSubscriber()
      bus.unsubscribe(sub, classifier) should be(false)
      disposeSubscriber(system, sub)
    }

    "not allow for the same subscriber to subscribe to the same channel twice" in {
      bus.subscribe(subscriber, classifier) should be(true)
      bus.subscribe(subscriber, classifier) should be(false)
      bus.unsubscribe(subscriber, classifier) should be(true)
    }

    "not allow for the same subscriber to unsubscribe to the same channel twice" in {
      bus.subscribe(subscriber, classifier) should be(true)
      bus.unsubscribe(subscriber, classifier) should be(true)
      bus.unsubscribe(subscriber, classifier) should be(false)
    }

    "allow to add multiple subscribers" in {
      val subscribers = (1 to 10) map { _ ⇒ createNewSubscriber() }
      val events = createEvents(10)
      val classifiers = events map getClassifierFor
      subscribers.zip(classifiers) forall { case (s, c) ⇒ bus.subscribe(s, c) } should be(true)
      subscribers.zip(classifiers) forall { case (s, c) ⇒ bus.unsubscribe(s, c) } should be(true)

      subscribers foreach (disposeSubscriber(system, _))
    }

    "publishing events without any subscribers shouldn't be a problem" in {
      bus.publish(event)
    }

    "publish the given event to the only subscriber" in {
      bus.subscribe(subscriber, classifier)
      bus.publish(event)
      expectMsg(event)
      expectNoMsg(1 second)
      bus.unsubscribe(subscriber, classifier)
    }

    "publish to the only subscriber multiple times" in {
      bus.subscribe(subscriber, classifier)
      bus.publish(event)
      bus.publish(event)
      bus.publish(event)
      expectMsg(event)
      expectMsg(event)
      expectMsg(event)
      expectNoMsg(1 second)
      bus.unsubscribe(subscriber, classifier)
    }

    "publish the given event to all intended subscribers" in {
      val range = 0 until 10
      val subscribers = range map (_ ⇒ createNewSubscriber())
      subscribers foreach { s ⇒ bus.subscribe(s, classifier) should be(true) }
      bus.publish(event)
      range foreach { _ ⇒ expectMsg(event) }
      subscribers foreach { s ⇒ bus.unsubscribe(s, classifier) should be(true); disposeSubscriber(system, s) }
    }

    "not publish the given event to any other subscribers than the intended ones" in {
      val otherSubscriber = createNewSubscriber()
      val otherClassifier = getClassifierFor(events.drop(1).head)
      bus.subscribe(subscriber, classifier)
      bus.subscribe(otherSubscriber, otherClassifier)
      bus.publish(event)
      expectMsg(event)
      bus.unsubscribe(subscriber, classifier)
      bus.unsubscribe(otherSubscriber, otherClassifier)
      expectNoMsg(1 second)
    }

    "not publish the given event to a former subscriber" in {
      bus.subscribe(subscriber, classifier)
      bus.unsubscribe(subscriber, classifier)
      bus.publish(event)
      expectNoMsg(1 second)
    }

    "cleanup subscriber" in {
      disposeSubscriber(system, subscriber)
    }
  }
}

object ActorEventBusSpec {
  class ComposedActorEventBus extends ActorEventBus with LookupClassification {
    type Event = Int
    type Classifier = String

    def classify(event: Event) = event.toString
    protected def mapSize = 32
    def publish(event: Event, subscriber: Subscriber) = subscriber ! event
  }
}

class ActorEventBusSpec extends EventBusSpec("ActorEventBus") {
  import akka.event.ActorEventBusSpec._
  import EventBusSpec.TestActorWrapperActor

  type BusType = ComposedActorEventBus
  def createNewEventBus(): BusType = new ComposedActorEventBus

  def createEvents(numberOfEvents: Int) = (0 until numberOfEvents)

  def createSubscriber(pipeTo: ActorRef) = system.actorOf(Props(new TestActorWrapperActor(pipeTo)))

  def classifierFor(event: BusType#Event) = event.toString

  def disposeSubscriber(system: ActorSystem, subscriber: BusType#Subscriber): Unit = system.stop(subscriber)

  busName must {

    val bus = createNewEventBus()

    "use unsubscriber, to unsubscribe terminated actors, when they terminate" in {
      val p, observer = TestProbe()
      val subs = createSubscriber(p.ref)
      val events = createEvents(20)
      val event = events.head

      bus.subscribe(subs, classifierFor(event))
    }
  }
}

object ScanningEventBusSpec {

  class MyScanningEventBus extends EventBus with ScanningClassification {
    type Event = Int
    type Subscriber = Procedure[Int]
    type Classifier = String

    protected def compareClassifiers(a: Classifier, b: Classifier): Int = a compareTo b
    protected def compareSubscribers(a: Subscriber, b: Subscriber): Int = akka.util.Helpers.compareIdentityHash(a, b)

    protected def matches(classifier: Classifier, event: Event): Boolean = event.toString == classifier

    protected def publish(event: Event, subscriber: Subscriber): Unit = subscriber(event)
  }
}

class ScanningEventBusSpec extends EventBusSpec("ScanningEventBus") {
  import ScanningEventBusSpec._

  type BusType = MyScanningEventBus

  def createNewEventBus(): BusType = new MyScanningEventBus

  def createEvents(numberOfEvents: Int) = (0 until numberOfEvents)

  def createSubscriber(pipeTo: ActorRef) = new Procedure[Int] { def apply(i: Int) = pipeTo ! i }

  def classifierFor(event: BusType#Event) = event.toString

  def disposeSubscriber(system: ActorSystem, subscriber: BusType#Subscriber): Unit = ()
}

object LookupEventBusSpec {
  class MyLookupEventBus extends EventBus with LookupClassification {
    type Event = Int
    type Subscriber = Procedure[Int]
    type Classifier = String

    override protected def classify(event: Int): String = event.toString
    override protected def compareSubscribers(a: Procedure[Int], b: Procedure[Int]): Int =
      akka.util.Helpers.compareIdentityHash(a, b)
    override protected def mapSize = 32
    override protected def publish(event: Int, subscriber: Procedure[Int]): Unit =
      subscriber(event)
  }
}

class LookupEventBusSpec extends EventBusSpec("LookupEventBus") {
  import LookupEventBusSpec._

  type BusType = MyLookupEventBus

  def createNewEventBus(): BusType = new MyLookupEventBus

  def createEvents(numberOfEvents: Int) = (0 until numberOfEvents)

  def createSubscriber(pipeTo: ActorRef) = new Procedure[Int] { def apply(i: Int) = pipeTo ! i }

  def classifierFor(event: BusType#Event) = event.toString

  def disposeSubscriber(system: ActorSystem, subscriber: BusType#Subscriber): Unit = ()
}

