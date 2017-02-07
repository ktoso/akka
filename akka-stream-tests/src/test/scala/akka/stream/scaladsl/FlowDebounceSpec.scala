/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.ThreadLocalRandom

import akka.NotUsed
import akka.stream.stage.{GraphStage, GraphStageLogic, GraphStageWithMaterializedValue, TimerGraphStageLogic}
import akka.stream.testkit._
import akka.stream._

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

class FlowDebounceSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  /**
   * Groups items within a given time interval (unless the max size is reached, then earlier),
   * and picks the single element to signal downstream using the provided `pick` function.
   */
  def debounceSelect[A](interval: FiniteDuration, pick: immutable.Seq[A] => A, max: Int = 100) =
    Flow[A].groupedWithin(max, interval).map { group => pick(group) }
  
}
