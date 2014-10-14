/**
 * Coptright (C) 2014 Ttpesafe Inc. <http://www.ttpesafe.com>
 */
package akka.stream.scaladsl2

object Problem {

  class Thing
  implicit val thing = new Thing

  // source
  trait Source[+Out] {
    def runWith(s: DrainWithKey[Out]) = ???
    def runWith(s: SimpleDrain[Out]) = ???
  }
  trait Tap[+Out] extends Source[Out]
  class SimpleTap[+Out]() extends Tap[Out]
  class TapWithKey[+Out]() extends Tap[Out]

  // sink
  trait Sink[-In] {
    def runWith(t: TapWithKey[In]) = ???
    def runWith(t: SimpleTap[In]) = ???
  }
  trait Drain[-In] extends Sink[In]
  class DrainWithKey[-In]() extends Drain[In]
  class SimpleDrain[-In]() extends Drain[In]

  // impls
  case class PublisherTap[Out]() extends SimpleTap[Out]
  case class SubscriberTap[Out]() extends TapWithKey[Out]

  case class PublisherDrain[In]() extends DrainWithKey[In]
  case class SubscriberDrain[In]() extends DrainWithKey[In]

  // tests
  val t: Sink[String] = ???
  t.runWith(PublisherTap())
  t.runWith(PublisherTap[String]())
  t.runWith(SubscriberTap())
  t.runWith(SubscriberTap[String]())

  val s: Source[String] = ???
  s.runWith(PublisherDrain()) // nope
  s.runWith(PublisherDrain[String]())
  s.runWith(SubscriberDrain()) // nope
  s.runWith(SubscriberDrain[String]())
}

/*
<console>:45: error: overloaded method value runWith with alternatives:
  (s: Problem.SimpleDrain[String])Nothing <and>
  (s: Problem.DrainWithKey[String])Nothing
 cannot be applied to (Problem.PublisherDrain[Nothing])
         s.runWith(PublisherDrain())
           ^
<console>:47: error: overloaded method value runWith with alternatives:
  (s: Problem.SimpleDrain[String])Nothing <and>
  (s: Problem.DrainWithKey[String])Nothing
 cannot be applied to (Problem.SubscriberDrain[Nothing])
         s.runWith(SubscriberDrain())


 */