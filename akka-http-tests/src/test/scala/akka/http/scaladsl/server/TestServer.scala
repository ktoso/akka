/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.stream.scaladsl._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.Employee
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import spray.json.DefaultJsonProtocol

object TestServer extends App {

  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off""")

  val secondConfig = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.log-dead-letters = off""")

  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import Directives._

  object EmployeeJsonProtocol extends DefaultJsonProtocol {
    implicit val employeeFormat = jsonFormat5(Employee.apply)
  }
  import EmployeeJsonProtocol._

  implicit def marshaller: Marshaller[Employee, ByteString] = SprayJsonSupport.sprayByteStringMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee] = SprayJsonSupport.sprayJsonUnmarshaller[Employee]

  val bindingFuture = Http().bindAndHandle({
    get {
      complete("OK")
    }
  }, interface = "localhost", port = 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.shutdown())

  def delay[T]: T ⇒ T = { in ⇒
    Thread.sleep(300)
    in
  }
}
