/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.annotation.{DoNotInherit, InternalApi}
import akka.event.Logging
import com.typesafe.config.Config

import scala.concurrent.duration._

object StreamRefSettings {

  /** Java API */
  def create(system: ActorSystem): StreamRefSettings = apply(system)
  /** Scala API */
  def apply(system: ActorSystem): StreamRefSettings = {
    apply(system.settings.config.getConfig("akka.stream.stream-refs"))
  }

  /** Java API */
  def create(c: Config): StreamRefSettings = apply(c)
  /** Scala API */
  def apply(c: Config): StreamRefSettings = {
    StreamRefSettingsImpl(
      bufferCapacity = c.getInt("buffer-capacity"),
      demandRedeliveryInterval = c.getDuration("demand-redelivery-interval", TimeUnit.MILLISECONDS).millis,
      idleTimeout = c.getDuration("idle-timeout", TimeUnit.MILLISECONDS).millis
    )
  }
}

@DoNotInherit
trait StreamRefSettings {
  def bufferCapacity: Int
  def demandRedeliveryInterval: FiniteDuration
  def idleTimeout: FiniteDuration

  // ---

  def withBufferCapacity(value: Int): StreamRefSettings
  def withDemandRedeliveryInterval(value: scala.concurrent.duration.FiniteDuration): StreamRefSettings
  def withIdleTimeout(value: FiniteDuration): StreamRefSettings
}

/** INTERNAL API */
@InternalApi
final case class StreamRefSettingsImpl private(
    override val bufferCapacity: Int,
    override val demandRedeliveryInterval: FiniteDuration,
    override val idleTimeout: FiniteDuration
  ) extends StreamRefSettings {

  override def withBufferCapacity(value: Int): StreamRefSettings = copy(bufferCapacity = value)
  override def withDemandRedeliveryInterval(value: scala.concurrent.duration.FiniteDuration): StreamRefSettings = copy(demandRedeliveryInterval = value)
  override def withIdleTimeout(value: FiniteDuration): StreamRefSettings = copy(idleTimeout = value)

  override def productPrefix: String = Logging.simpleName(classOf[StreamRefSettings])
}
