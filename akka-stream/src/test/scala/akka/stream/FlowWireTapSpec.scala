/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec, ScriptedTest }
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.impl.ActorBasedFlowMaterializer

class FlowWireTapSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  val gen = new ActorBasedFlowMaterializer(settings, system)

  "A Map" must {

    "map" in {
      var sum = 0

      def script = Script((1 to 50) map { x ⇒ Seq(x) -> Seq(x) }: _*)
      (1 to 2) foreach (_ ⇒ runScript(script, settings)(_.wireTap { n ⇒ println("n=" + n); sum += n }))

      sum should equal(1000)
    }
  }

}