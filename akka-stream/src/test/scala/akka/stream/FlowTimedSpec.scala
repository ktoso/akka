/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.{ StreamTestKit, ScriptedTest }
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.Flow

class FlowTimedSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  val gen = new ActorBasedFlowMaterializer(settings, system)

  "A Timed" must {

    "measure time it takes to go through intermediate" in {
      import pattern.timed._

      def script = Script((1 to 50000) map { x ⇒ Seq(x) -> Seq(x) }: _*)
      (1 to 2) foreach (_ ⇒ runScript(script, settings)(_.map(identity).timed(_ % 1000 == 0)))

      // todo verify
    }

  }

}

