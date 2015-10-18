/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server.directives

import akka.http.scaladsl.marshalling.{ Marshal, ToEntityMarshaller }
import akka.http.scaladsl.model.{ ContentTypeRange, ContentTypes, HttpEntity, HttpRequest }
import akka.http.scaladsl.server.{ FramingWithContentType, JsonStreamingRenderingMode, Route }
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller, _ }
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{ FlattenStrategy, Source }
import akka.util.ByteString

/**
 * Extends entity() directive to support `stream[T]` for framed messages.
 * See also `JsonEntityStreamingSupport`.
 */
trait EntityStreamingDirectives extends MarshallingDirectives {

  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

  // TODO provide overloads for explicit framing

  // TODO materialized value may want to be "drain/cancel" or something like it?

  // TODO DOCS
  // TODO could expose parallelism
  // TODO could expose mapAsyncUnordered version!
  def stream[T](implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, Any]] =
    stream[T](parallelism = 1)(um, framing)

  def stream[T](framing: FramingWithContentType, parallelism: Int = 1)(implicit um: Unmarshaller[ByteString, T]): FromRequestUnmarshaller[Source[T, Any]] =
    stream[T](parallelism)(um, framing)

  // format: OFF
  def stream[T](parallelism: Int = 1)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, Any]] =
    Unmarshaller.withMaterializer[HttpRequest, Source[T, Any]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (!framing.supports(entity.contentType)) {
        val supportedContentTypes = framing.supported.map(ContentTypeRange(_))
        FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(supportedContentTypes))
      } else {
        val stream = entity.dataBytes.via(framing.flow).mapAsync(1)(Unmarshal(_).to[T])
        FastFuture.successful(stream)
      }
    }
  // format: ON

  // TODO could expose `streamMat`, for more fine grained picking of Marshaller -- ktoso

  // format: OFF
  // TODO DOCS
  // TODO this is how Unordered could look like, TODO DRY it up
  def streamUnordered[T](parallelism: Int = 1)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, Any]] =
    Unmarshaller.withMaterializer[HttpRequest, Source[T, Any]] { implicit ec ⇒ implicit mat ⇒ req ⇒
      val entity = req.entity
      if (!framing.supports(entity.contentType)) {
        val supportedContentTypes = framing.supported.map(ContentTypeRange(_))
        FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(supportedContentTypes))
      } else {
        val stream = entity.dataBytes.via(framing.flow).mapAsyncUnordered(parallelism)(Unmarshal(_).to[T])
        FastFuture.successful(stream)
      }
    }


  // TODO note to self - we need the same of ease of streaming stuff for the client side - i.e. the twitter firehose case.

  // TODO we could expose parallelism here!
  // TODO enable this on complete()
  // TODO want to make this work as: complete(stream(source, marshallingParallelism)
  // TODO want to make this work as: complete(streamUnordered(marshallingParallelism)(source)
  // TODO the complete(source) must be detached from json, the read-side is already :)
  // TODO   enables trivial xml streaming as well as csv/anything streaming etc. That'll be awesome.
  def completeStreaming[T](ts: Source[T, Any])(implicit m: ToEntityMarshaller[T], mode: JsonStreamingRenderingMode = JsonStreamingRenderingMode.LineByLine): Route = {
    extractExecutionContext { implicit ec ⇒
      val marshalledSource = ts
        .mapAsync(1)(t ⇒ Marshal(t).to[HttpEntity])
        .map(_.dataBytes)
        .flatten(FlattenStrategy.concat)
        .intersperse(mode.start, mode.between, mode.end)
      complete(HttpEntity(ContentTypes.`application/json`, marshalledSource))
    }
  }

  def completeStreaming[T](ts: Source[T, Any], mode: JsonStreamingRenderingMode)(implicit m: ToEntityMarshaller[T]): Route =
    completeStreaming(ts)(m, mode)

}

object EntityStreamingDirectives extends EntityStreamingDirectives
