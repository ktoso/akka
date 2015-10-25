package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.model.{ ContentType, ContentTypes }
import akka.http.scaladsl.server.directives.EntityStreamingDirectives.SourceRenderingMode
import akka.http.scaladsl.unmarshalling.{ Unmarshaller, Unmarshal }
import akka.stream.Materializer
import akka.stream.io.Framing
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.ExecutionContext

/**
 * Same as [[Framing]] but additionally can express which [[ContentType]] it supports,
 * which can be used to reject routes if content type does not match used framing.
 */
abstract class FramingWithContentType extends Framing { // TODO javadsl
  def flow: Flow[ByteString, ByteString, Unit]
  def supported: immutable.Set[ContentType]
  def supports(ct: ContentType): Boolean = supported.contains(ct)
  def isSupported(ct: akka.http.javadsl.model.ContentType): Boolean = supports(JavaMapping.ContentType.toScala(ct))
}
object FramingWithContentType {
  def apply(framing: Flow[ByteString, ByteString, Any], contentType: ContentType, moreContentTypes: ContentType*) =
    new FramingWithContentType {
      // TODO javadsl
      override def flow: Flow[ByteString, ByteString, Unit] = framing.mapMaterializedValue(_ ⇒ ())

      override def supported: Set[ContentType] =
        if (moreContentTypes.isEmpty) Set(contentType)
        else Set(contentType) ++ moreContentTypes
    }
}

trait JsonEntityStreamingSupport {

  /** `application/json` specific Framing implementation */
  implicit object JsonFraming extends FramingWithContentType { // TODO
    override final val flow = Flow[ByteString].via(Framing.json(Int.MaxValue))

    override def supports(ct: ContentType): Boolean = ct match {
      case ContentTypes.`application/json` ⇒ true
      case _                               ⇒ false
    }

    final val _supported = Set(ContentTypes.`application/json`)

    override def supported: Set[ContentType] = _supported
  }

  // TODO explicitly pick JSON framing here?
  def framingOf[T](implicit m: Unmarshaller[ByteString, T], framing: FramingWithContentType, ec: ExecutionContext, mat: Materializer): Flow[ByteString, T, Unit] =
    Flow[ByteString].via(framing.flow).mapAsync(1)(el ⇒ Unmarshal(el).to[T])

}

/**
 * Specialised rendering mode for streaming elements as JSON.
 *
 * See also: <a href="https://en.wikipedia.org/wiki/JSON_Streaming">JSON Streaming on Wikipedia</a>.
 */
trait JsonSourceRenderingMode extends SourceRenderingMode

// TODO framing needs a max-entity size.
object JsonSourceRenderingMode {

  /**
   * Most compact rendering mode
   * It does not intersperse any separator between the signalled elements.
   *
   * {{{
   * {"id":42}{"id":43}{"id":44}
   * }}}
   */
  object Compact extends JsonSourceRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString.empty
    override def end: ByteString = ByteString.empty
  }

  /**
   * Simple rendering mode, similar to [[Compact]] however interspersing elements with a `\n` character.
   *
   * {{{
   * {"id":42},{"id":43},{"id":44}
   * }}}
   */
  object CompactCommaSeparated extends JsonSourceRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString(",")
    override def end: ByteString = ByteString.empty
  }

  /**
   * Rendering mode useful when the receiving end expects a valid JSON Array.
   * It can be useful when the client wants to detect when the stream has been successfully received in-full,
   * which it can determine by seeing the terminating `]` character.
   *
   * The framing's terminal `]` will ONLY be emitted if the stream has completed successfully,
   * in other words - the stream has been emitted completely, without errors occuring before the final element has been signaled.
   *
   * {{{
   * [{"id":42},{"id":43},{"id":44}]
   * }}}
   */
  object CompactArray extends JsonSourceRenderingMode {
    override def start: ByteString = ByteString("[")
    override def between: ByteString = ByteString(",")
    override def end: ByteString = ByteString("]")
  }

  /**
   * Recommended rendering mode.
   *
   * It is a nice balance between valid and human-readable as well as resonably small size overhead (just the `\n` between elements).
   * A good example of API's using this syntax is Twitter's Firehose (last verified at 1.1 version of that API).
   *
   * {{{
   * {"id":42}
   * {"id":43}
   * {"id":44}
   * }}}
   */
  object LineByLine extends JsonSourceRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString("\n")
    override def end: ByteString = ByteString.empty
  }

  /**
   * Simple rendering mode interspersing each pair of elements with both `,\n`.
   * Picking the [[LineByLine]] format may be preferable, as it is slightly simpler to parse - each line being a valid json object (no need to trim the comma).
   *
   * {{{
   * {"id":42},
   * {"id":43},
   * {"id":44}
   * }}}
   */
  object LineByLineCommaSeparated extends JsonSourceRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString(",\n")
    override def end: ByteString = ByteString.empty
  }

}

object JsonEntityStreamingSupport extends JsonEntityStreamingSupport

object JsonStreamingSettings {

  def apply(sys: ActorSystem): JsonStreamingSettings =
    apply(sys.settings.config.getConfig("akka.http.json-streaming"))

  def apply(c: Config): JsonStreamingSettings = {
    JsonStreamingSettings(
      c.getInt("max-object-size"),
      renderingMode(c.getString("rendering-mode")))
  }

  def renderingMode(name: String): SourceRenderingMode = name match {
    case "line-by-line"                 ⇒ JsonSourceRenderingMode.LineByLine // the default
    case "line-by-line-comma-separated" ⇒ JsonSourceRenderingMode.LineByLineCommaSeparated
    case "compact"                      ⇒ JsonSourceRenderingMode.Compact
    case "compact-comma-separated"      ⇒ JsonSourceRenderingMode.CompactCommaSeparated
    case "compact-array"                ⇒ JsonSourceRenderingMode.CompactArray
  }
}
final case class JsonStreamingSettings(
  maxObjectSize: Int,
  style: SourceRenderingMode)

