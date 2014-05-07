/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.pattern

import akka.stream.scaladsl.{ Transformer, Flow }
import akka.stream.impl.FlowImpl
import scala.collection.immutable

import scala.language.implicitConversions

/* >>>>> EARLY DRAFT <<<<< */
class TimedDSL[O](val flow: Flow[O]) {
  import flow._

  def timed(matches: O ⇒ Boolean): Flow[O] = {
    transform(new Transformer[O, O] {
      private var prevNanos = 0L
      private var count = 0L

      override def onNext(in: O) = {
        count += 1
        if (matches(in))
          print(in)
        immutable.Seq(in)
      }

      // todo would be configurable
      private def print(in: O): Unit = {
        import concurrent.duration._
        val now = System.nanoTime()
        println(s"got [$in] at ${(now - prevNanos).nanos}, avg: ${(now - prevNanos).toDouble / count}")
        prevNanos = now
      }
    })
  }

}

object timed {
  implicit def enrichWithTimed[O](f: Flow[O]) = new TimedDSL(f)
}
