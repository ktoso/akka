/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server.directives

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.FramingWithContentType
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller, _ }
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{ FlattenStrategy, Source }
import akka.util.ByteString

/**
 * Allows the [[MarshallingDirectives.entity]] directive to extract a `stream[T]` for framed messages.
 * See `JsonEntityStreamingSupport` and classes extending it, such as `SprayJsonSupport` to get marshallers.
 */
trait EntityStreamingDirectives extends MarshallingDirectives {

  import EntityStreamingDirectives._

  // TODO provide overloads for explicit framing

  // TODO materialized value may want to be "drain/cancel" or something like it?

  // TODO DOCS
  def stream[T](framing: FramingWithContentType)(implicit um: Unmarshaller[ByteString, T]): FromRequestUnmarshaller[Source[T, Any]] =
    stream[T](um, framing)

  def stream[T](implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, Any]] =
    streamAsyncUnordered(1)(um, framing)

  // TODO could expose `streamMat`, for more fine grained picking of Marshaller -- ktoso

  // format: OFF
  // TODO DOCS
  // TODO this is how Unordered could look like, TODO DRY it up
  def streamAsyncUnordered[T](parallelism: Int)(implicit um: Unmarshaller[ByteString, T], framing: FramingWithContentType): FromRequestUnmarshaller[Source[T, Any]] =
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

  // format: ON

  // TODO note to self - we need the same of ease of streaming stuff for the client side - i.e. the twitter firehose case.

  implicit def sourceMarshaller[T](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[Source[T, Any]] =
    Marshaller[Source[T, Any], HttpResponse] { implicit ec ⇒
      source ⇒
        FastFuture successful {
          Marshalling.WithFixedCharset(MediaTypes.`application/json`, HttpCharsets.`UTF-8`, () ⇒ {
            // TODO remove content type from here
            val bytes = source
              .mapAsync(1)(t ⇒ Marshal(t).to[HttpEntity])
              .map(_.dataBytes)
              .flatten(FlattenStrategy.concat)
              .intersperse(mode.start, mode.between, mode.end)
            HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, bytes))
          }) :: Nil
        }
    }

  implicit def sourceParallelismMarshaller[T](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[AsyncRenderingOf[T]] =
    Marshaller[AsyncRenderingOf[T], HttpResponse] { implicit ec ⇒
      rendering ⇒
        FastFuture successful {
          Marshalling.WithFixedCharset(MediaTypes.`application/json`, HttpCharsets.`UTF-8`, () ⇒ {
            // TODO remove content type from here
            val bytes = rendering.source
              .mapAsync(rendering.parallelism)(t ⇒ Marshal(t).to[HttpEntity])
              .map(_.dataBytes)
              .flatten(FlattenStrategy.concat)
              .intersperse(mode.start, mode.between, mode.end)
            HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, bytes))
          }) :: Nil
        }
    }

  implicit def sourceUnorderedMarshaller[T](implicit m: ToEntityMarshaller[T], mode: SourceRenderingMode): ToResponseMarshaller[AsyncUnorderedRenderingOf[T]] =
    Marshaller[AsyncUnorderedRenderingOf[T], HttpResponse] { implicit ec ⇒
      rendering ⇒
        FastFuture successful {
          Marshalling.WithFixedCharset(MediaTypes.`application/json`, HttpCharsets.`UTF-8`, () ⇒ {
            // TODO remove content type from here
            val bytes = rendering.source
              .mapAsync(rendering.parallelism)(t ⇒ Marshal(t).to[HttpEntity])
              .map(_.dataBytes)
              .flatten(FlattenStrategy.concat)
              .intersperse(mode.start, mode.between, mode.end)
            HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, bytes))
          }) :: Nil
        }
    }

  // special rendering modes

  implicit def enableSpecialSourceRenderingModes[T](source: Source[T, Any]): EnableSpecialSourceRenderingModes[T] =
    new EnableSpecialSourceRenderingModes(source)

}
object EntityStreamingDirectives extends EntityStreamingDirectives {
  /**
   * Defines ByteStrings to be injected before the first, between, and after all elements of a [[Source]],
   * when used to complete a request.
   *
   * A typical example would be rendering a ``Source[T, _]`` as JSON array,
   * where start is `[`, between is `,`, and end is `]` - which procudes a valid json array, assuming each element can
   * be properly marshalled as JSON object.
   */
  trait SourceRenderingMode {
    def start: ByteString
    def between: ByteString
    def end: ByteString
  }

  final class AsyncRenderingOf[T](val source: Source[T, Any], val parallelism: Int)
  final class AsyncUnorderedRenderingOf[T](val source: Source[T, Any], val parallelism: Int)

}

final class EnableSpecialSourceRenderingModes[T](val source: Source[T, Any]) extends AnyVal {
  def renderAsync(parallelism: Int) = new EntityStreamingDirectives.AsyncRenderingOf(source, parallelism)
  def renderAsyncUnordered(parallelism: Int) = new EntityStreamingDirectives.AsyncUnorderedRenderingOf(source, parallelism)
}