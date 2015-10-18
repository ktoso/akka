package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.model.{ ContentType, ContentTypes }
import akka.http.scaladsl.server.JsonEntityStreamingSupport._
import akka.stream.io.Framing
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.immutable

/**
 * Same as [[Framing]] but additionally can express which [[ContentType]] it supports,
 * which can be used to reject routes if content type does not match used framing.
 */
trait FramingWithContentType extends Framing { // TODO javadsl
  def flow: Flow[ByteString, ByteString, Unit]
  def supported: immutable.Set[ContentType]
  def supports(ct: ContentType): Boolean
  def isSupported(ct: akka.http.javadsl.model.ContentType): Boolean = supports(JavaMapping.ContentType.toScala(ct))
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

}

// TODO framing needs a max-entity size.
sealed trait JsonStreamingRenderingMode {
  def start: ByteString
  def between: ByteString
  def end: ByteString
}

object JsonStreamingRenderingMode {

  /**
   * // TODO ACTUAL DOCS
   * {{{
   * {"id":42}{"id":43}{"id":44}
   * }}}
   */
  object Compact extends JsonStreamingRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString.empty
    override def end: ByteString = ByteString.empty
  }

  /**
   * {{{
   * {"id":42},{"id":43},{"id":44}
   * }}}
   */
  object CompactCommaSeparated extends JsonStreamingRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString(",")
    override def end: ByteString = ByteString.empty
  }

  /**
   * {{{
   * [{"id":42},{"id":43},{"id":44}]
   * }}}
   */
  object CompactArray extends JsonStreamingRenderingMode {
    override def start: ByteString = ByteString("[")
    override def between: ByteString = ByteString(",")
    override def end: ByteString = ByteString("]")
  }

  /**
   *
   * This is the recommended setting, as it's a nice balance between valid / nicely readable as well as resonably small size overhead.
   * A good example of API's using this syntax is Twitter's Firehose (last verified at 1.1 version of that API).
   *
   * {{{
   * {"id":42}
   * {"id":43}
   * {"id":44}
   * }}}
   */
  object LineByLine extends JsonStreamingRenderingMode {
    override def start: ByteString = ByteString.empty
    override def between: ByteString = ByteString("\n")
    override def end: ByteString = ByteString.empty
  }

  /**
   * {{{
   * {"id":42},
   * {"id":43},
   * {"id":44}
   * }}}
   */
  object LineByLineCommaSeparated extends JsonStreamingRenderingMode {
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

  def renderingMode(name: String): JsonStreamingRenderingMode = name match {
    case "line-by-line"                 ⇒ JsonStreamingRenderingMode.LineByLine // the default
    case "line-by-line-comma-separated" ⇒ JsonStreamingRenderingMode.LineByLineCommaSeparated
    case "compact"                      ⇒ JsonStreamingRenderingMode.Compact
    case "compact-comma-separated"      ⇒ JsonStreamingRenderingMode.CompactCommaSeparated
    case "compact-array"                ⇒ JsonStreamingRenderingMode.CompactArray
  }
}
final case class JsonStreamingSettings(
  maxObjectSize: Int,
  style: JsonStreamingRenderingMode)

