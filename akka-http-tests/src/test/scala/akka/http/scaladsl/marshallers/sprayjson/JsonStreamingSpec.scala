/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.scaladsl.marshallers.Employee
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.server.{ Directives, JsonStreamingDirectives }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.{ Matchers, WordSpec }
import spray.json.DefaultJsonProtocol

class JsonStreamingSpec extends WordSpec with Matchers with ScalatestRouteTest
  with Directives with JsonStreamingDirectives {

  object EmployeeJsonProtocol extends DefaultJsonProtocol {
    implicit val employeeFormat = jsonFormat5(Employee.apply)
  }
  import EmployeeJsonProtocol._

  implicit def marshaller: Marshaller[Employee, ByteString] = SprayJsonSupport.sprayByteStringMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee] = SprayJsonSupport.sprayJsonUnmarshaller[Employee]

  "JsonStreaming" should {
    "read" in {
      val route = post {
        // TODO how to hide this implicitly?
        jsonStream[Employee](implicitly[Unmarshaller[ByteString, Employee]]) { employees ⇒
          complete(employees.runFold("")(_ + _.fname + ","))
        }
      }

      val json =
        """|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false}
        """.stripMargin

      Post("/", json) ~> route ~> check {
        responseAs[String] shouldEqual "Frank,"
      }
    }

    "write" in {
      val frank = Employee.simple

      val route = get {
        complete(Source.repeat(frank).take(3))
      }

      val json =
        """|{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345},
           |{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345},
           |{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345},
           |""".stripMargin

      Get("/") ~> route ~> check {
        responseAs[String] shouldEqual json
      }
    }
  }
}
