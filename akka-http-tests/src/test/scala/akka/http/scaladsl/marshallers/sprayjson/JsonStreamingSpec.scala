/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.scaladsl.marshallers.Employee
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.model.{ ContentTypes, StatusCodes }
import akka.http.scaladsl.server.{ JsonStreamingRenderingMode, Directives, UnsupportedRequestContentTypeRejection }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.{ Matchers, WordSpec }
import spray.json.{ DefaultJsonProtocol, JsObject }

class JsonStreamingSpec extends WordSpec with Matchers with ScalatestRouteTest
  with Directives with SprayJsonSupport {

  object EmployeeJsonProtocol extends DefaultJsonProtocol {
    implicit val employeeFormat = jsonFormat5(Employee.apply)
  }
  import EmployeeJsonProtocol._

  implicit def marshaller: Marshaller[Employee, ByteString] = SprayJsonSupport.sprayByteStringMarshaller[Employee]
  implicit def unmarshaller: FromEntityUnmarshaller[Employee] = SprayJsonSupport.sprayJsonUnmarshaller[Employee]

  val PostJson = (path: String, data: String) ⇒
    Post(path).withEntity(ContentTypes.`application/json`, data)

  val lineByLineCommaSeparated =
    """|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false},
       |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false},
       |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}
    """.stripMargin

  val lineByLine =
    """|{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false}
       |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false}
       |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}
    """.stripMargin

  val arrayPretty =
    """|[
       |{"fname":"Frank","name":"Smith","age":42,"id":1337,"boardMember":false}
       |{"fname":"Bob","name":"Smith","age":42,"id":1337,"boardMember":false}
       |{"fname":"Hank","name":"Smith","age":42,"id":1337,"boardMember":false}
       |]""".stripMargin

  "JsonStreaming" should {
    "read using entity(stream[T])" in {
      val route = post {
        entity(stream[Employee]) { employees ⇒
          complete(employees.intersperse(",").runFold("")(_ + _))
        }
      }

      PostJson("/", lineByLineCommaSeparated) ~> route ~> check {
        responseAs[String] shouldEqual "Frank,Bob,Hank"
      }
    }

    "read entity(stream[JsObject])" in {
      val route = post {
        entity(stream[JsObject]) { js ⇒
          complete(js.runFold("")({ case (acc, x) ⇒ acc + "." }))
        }
      }

      PostJson("/", lineByLineCommaSeparated) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "reject when content type is not JSON" in {
      val route = post {
        entity(stream[Employee]) { employees ⇒
          complete(employees.map(_.fname).intersperse(",").runFold("")(_ + _))
        }
      }

      Post("/").withEntity(ContentTypes.`text/plain`, "Hello world!") ~> route ~> check {
        handled shouldEqual false
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(ContentTypes.`application/json`))
      }
    }

    "read using explicit framing" in {
      val route = post {
        entity(stream[Employee](framing = JsonFraming)) { employees ⇒
          complete(employees.map(_.fname).intersperse(",").runFold("")(_ + _))
        }
      }

      PostJson("/", lineByLineCommaSeparated) ~> route ~> check {
        responseAs[String] shouldEqual "Frank,Bob,Hank"
      }
    }

    "write streamed json line by line" in {
      val frank = Employee.simple

      val route = get {
        completeStreaming(Source.repeat(frank).take(3))
      }

      val json =
        """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345}""" + "\n" +
          """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345}""" + "\n" +
          """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345}""" // TODO what if someone wants eager newline?

      Get("/") ~> route ~> check {
        responseAs[String] shouldEqual json
      }
    }

    "write streaming json using given rendering mode" in {
      val frank = Employee.simple

      implicit val renderingMode = JsonStreamingRenderingMode.CompactArray

      val routeImplicitly = get {
        completeStreaming(Source.repeat(frank).take(3))
      }
      val routeExplicitly = get {
        completeStreaming(Source.repeat(frank).take(3), renderingMode)
      }

      val json =
        """[""" +
          """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345},""" +
          """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345},""" +
          """{"name":"Smith","boardMember":false,"fname":"Frank","age":42,"id":12345}""" +
          """]"""

      Get("/") ~> routeExplicitly ~> check {
        responseAs[String] shouldEqual json
      }
      Get("/") ~> routeImplicitly ~> check {
        responseAs[String] shouldEqual json
      }
    }
  }
}
