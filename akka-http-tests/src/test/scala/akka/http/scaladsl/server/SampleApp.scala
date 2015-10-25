package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.{ Config, ConfigFactory }

abstract class SampleApp extends App {

  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off""")

  implicit val system = ActorSystem("ServerTest", testConf)
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

}
