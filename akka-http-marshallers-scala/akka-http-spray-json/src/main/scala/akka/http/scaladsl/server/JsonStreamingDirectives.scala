/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{ Marshal, ToEntityMarshaller }
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.scaladsl.{ FlattenStrategy, Flow, Source }
import akka.util.ByteString

trait JsonStreamingDirectives extends SprayJsonSupport {

  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  def JsonFraming = Flow[ByteString].transform(() ⇒ new JsonCollectingStage)

  def jsonStream[T](implicit um: Unmarshaller[ByteString, T]): Directive1[Source[T, Any]] = {
    extractRequestContext.flatMap { ctx ⇒
      import ctx.{ executionContext, materializer }
      provide(ctx.request.entity.dataBytes.via(JsonFraming).mapAsync(1)(Unmarshal(_).to[T]))
    }
  }

  // TODO enable this on complete()
  def completeStreamingJson[T](ts: Source[T, Any])(implicit m: ToEntityMarshaller[T]): Route = {
    extractExecutionContext { implicit ec ⇒
      val marshalledSource = ts
        .mapAsync(1)(t ⇒ Marshal(t).to[HttpEntity])
        .map(_.dataBytes)
        .flatten(FlattenStrategy.concat)
        .map(_ ++ ByteString(",\n"))
      //        .intersperse(",\n")
      complete(HttpEntity(ContentTypes.`application/json`, marshalledSource))
    }
  }

  //  implicit def sourceMarshaller[T](implicit m: Marshaller[T]): ToResponseMarshallable = {
  //    Marshaller.
  //  }
}
object JsonStreamingDirectives extends JsonStreamingDirectives