/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import java.io.File

import akka.http.scaladsl.model.Multipart
import akka.stream.scaladsl.{ Source, Sink }
import akka.util.ByteString
import directives._

import scala.util.Try

trait Directives extends RouteConcatenation
  with BasicDirectives
  with CacheConditionDirectives
  with CookieDirectives
  with DebuggingDirectives
  with CodingDirectives
  with ExecutionDirectives
  with FileAndResourceDirectives
  with FormFieldDirectives
  with FutureDirectives
  with HeaderDirectives
  with HostDirectives
  with MarshallingDirectives
  with MethodDirectives
  with MiscDirectives
  with ParameterDirectives
  with PathDirectives
  with RangeDirectives
  with RespondWithDirectives
  with RouteDirectives
  with SchemeDirectives
  with SecurityDirectives
  with WebsocketDirectives {

  case class UploadedFile(a: Any, b: Any, c: Any)

  def uploadedFile(destination: File): Directive1[Try[UploadedFile]] =

    extractRequestContext.flatMap { ctx ⇒
      import ctx.executionContext
      import ctx.materializer

      uploadedFiles(1).flatMap { source ⇒

        val uploaded = source.mapAsync(1) {
          case (info, fileSource) ⇒
            fileSource.runWith(Sink.file(destination)).map(size ⇒
              UploadedFile(info, destination, size))

        }

        onComplete(uploaded.runWith(Sink.head[UploadedFile]))
      }
    }

  case class FileInfo(a: Any)

  def uploadedFiles(maxFiles: Int): Directive1[Source[(FileInfo, Source[ByteString, Any]), Any]] =
    entity(as[Multipart.FormData]).map { formData ⇒
      formData.parts.filter(_.filename.isDefined)
        .take(maxFiles)
        .map[(FileInfo, Source[ByteString, Any])] { part ⇒
          (FileInfo(part.name, part.filename.get, part.entity.contentType), part.entity.dataBytes)
        }
    }
}

object Directives extends Directives
