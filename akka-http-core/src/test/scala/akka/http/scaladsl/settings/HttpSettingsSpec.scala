/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import scala.concurrent.duration._

class HttpSettingsSpec extends WordSpec with Matchers {

  def config(s: String) = ConfigFactory.parseString(s).withFallback(ConfigFactory.load()).resolve

  "akka.http.idle-timeout" should {
    val conf = config("""
      akka.http {
        idle-timeout = 13 ms
      }
    """)

    "not affect ConnectionPoolSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ConnectionPoolSettings.connectionSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.connectionSettings.idleTimeout should !==(13.millis)
    }
    "not affect ClientConnectionSettings.idleTimeout" in {
      val c = ClientConnectionSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ServerSettings.idleTimeout" in {
      val c = ServerSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
  }

  "akka.http.server.idle-timeout" should {
    val conf = config("""
      akka.http {
        server {
          idle-timeout = 13 ms
        }
      }
    """)

    "not affect ConnectionPoolSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ConnectionPoolSettings.connectionSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.connectionSettings.idleTimeout should !==(13.millis)
    }
    "not affect ClientConnectionSettings.idleTimeout" in {
      val c = ClientConnectionSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "affect ServerSettings.idleTimeout" in {
      val c = ServerSettings(conf)
      c.idleTimeout should ===(13.millis)
    }
  }

  "akka.http.client.idle-timeout" should {
    val conf = config("""
      akka.http {
        client {
          idle-timeout = 13 ms
        }
      }
    """)

    "not affect ConnectionPoolSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ConnectionPoolSettings.connectionSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.connectionSettings.idleTimeout should !==(13.millis)
    }
    "affect ClientConnectionSettings.idleTimeout" in {
      val c = ClientConnectionSettings(conf)
      c.idleTimeout should ===(13.millis)
    }
    "not affect ServerSettings.idleTimeout" in {
      val c = ServerSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
  }

  "akka.http.host-connection-pool.idle-timeout" should {
    val conf = config("""
      akka.http {
        host-connection-pool {
          idle-timeout = 13 ms
        }
      }
    """)

    "not affect ConnectionPoolSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.idleTimeout should ===(13.millis)
    }
    "affect ConnectionPoolSettings.connectionSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.connectionSettings.idleTimeout should !==(13.millis)
    }
    "affect ClientConnectionSettings.idleTimeout" in {
      val c = ClientConnectionSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ServerSettings.idleTimeout" in {
      val c = ServerSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
  }

  "akka.http.host-connection-pool.client.idle-timeout" should {
    val conf = config("""
      akka.http {
        host-connection-pool {
          client {
            idle-timeout = 13 ms
          }
        }
      }
    """)

    "not affect ConnectionPoolSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "affect ConnectionPoolSettings.connectionSettings.idleTimeout" in {
      val c = ConnectionPoolSettings(conf)
      c.connectionSettings.idleTimeout should ===(13.millis)
    }
    "affect ClientConnectionSettings.idleTimeout" in {
      val c = ClientConnectionSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
    "not affect ServerSettings.idleTimeout" in {
      val c = ServerSettings(conf)
      c.idleTimeout should !==(13.millis)
    }
  }

}