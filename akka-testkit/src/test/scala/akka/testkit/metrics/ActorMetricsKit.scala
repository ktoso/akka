/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import akka.testkit.AkkaSpec

import com.typesafe.config.Config

/**
 * Convinience trait for using [[MetricsKit]] with [[AkkaSpec]].
 * Configuration and shutdown behaviors are already defined for it.
 */
private[akka] trait ActorMetricsKit extends MetricsKit {
  this: AkkaSpec ⇒

  override def metricsConfig: Config = system.settings.config

  override def afterTermination() {
    shutdownMetrics()
  }
}
