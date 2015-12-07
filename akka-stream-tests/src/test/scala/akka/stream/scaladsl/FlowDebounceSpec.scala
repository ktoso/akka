/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.{ AkkaSpec, ScriptedTest }

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowDebounceSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)

  "A Debounce" must {

    "de duplicate elements" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒
        val seq = Seq.fill(100)(random.nextInt(0, 10))
        val res = seq.drop(1).foldLeft(List(seq.head)) { (acc, el) ⇒
          if (el != acc.head) el :: acc
          else acc
        }

        seq -> res.reverse
      }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.collect { case x if x % 2 == 0 ⇒ (x * x).toString }))
    }

  }

}
