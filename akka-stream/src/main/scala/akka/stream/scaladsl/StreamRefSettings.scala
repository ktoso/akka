/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

import scala.concurrent.duration._

final class StreamRefSettings(config: Config) {
  private val c = config.getConfig("akka.stream.stream-refs")

  val bufferCapacity = c.getInt("buffer-capacity")

  val demandRedeliveryInterval = c.getDuration("demand-redelivery-interval", TimeUnit.MILLISECONDS).millis

  val idleTimeout = c.getDuration("idle-timeout", TimeUnit.MILLISECONDS).millis
}
