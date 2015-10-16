package akka.http.scaladsl.server

import akka.http.scaladsl.server.JsonCollectingBuffer.{ InvalidJson, BufferPopResult, ByteValue }
import akka.util.ByteString

import scala.util.{ Failure, Success, Try }

object JsonCollectingBuffer {
  type BufferPopResult = Try[Option[ByteString]]

  object ByteValue {
    val SquareBraceStart = "[".getBytes.head
    val SquareBraceEnd = "]".getBytes.head
    val CurlyBraceStart = "{".getBytes.head
    val CurlyBraceEnd = "}".getBytes.head
    val DoubleQuote = "\"".getBytes.head
    val Backslash = "\\".getBytes.head
    val Comma = ",".getBytes.head

    object Whitespace {
      val LineBreak = "\n".getBytes.head
      val LineBreak2 = "\r".getBytes.head
      val Tab = "\t".getBytes.head
      val Space = " ".getBytes.head

      val All = Seq(LineBreak, LineBreak2, Tab, Space)

      def isWhitespace(input: Byte): Boolean =
        All.contains(input)
    }
  }

  case class InvalidJson(value: ByteString) extends RuntimeException
}

class JsonCollectingBuffer {

  private var buffer: ByteString = ByteString("")
  private var completedObjectIndexes: Seq[Int] = Seq.empty

  private var isValid: Boolean = true
  private var isStartOfStringExpression: Boolean = false
  private var isStartOfEscapeSequence: Boolean = false
  private var objectDepthLevel = 0

  def append(input: ByteString): Unit =
    if (isValid && input.nonEmpty) {
      var idx = 0
      val length = input.length
      while (idx < length) {
        appendByte(input(idx))
        idx += 1
      }
    }

  def pop: BufferPopResult =
    if (isValid) {
      Success(
        for {
          index ← completedObjectIndexes.headOption
        } yield {
          val result = buffer.slice(0, index)
          buffer = buffer.slice(index, buffer.length)
          completedObjectIndexes = completedObjectIndexes.tail.map(_ - index)
          result
        })
    } else
      Failure(InvalidJson(buffer))

  def valid: Boolean =
    isValid

  private def appendByte(input: Byte): Unit =
    input match {
      case ByteValue.SquareBraceStart if !isStartOfObject ⇒
      // do nothing

      case ByteValue.SquareBraceEnd if !isStartOfObject   ⇒
      // do nothing

      case ByteValue.Comma if !isStartOfObject            ⇒
      // do nothing

      case ByteValue.Backslash ⇒
        isStartOfEscapeSequence = true
        buffer ++= ByteString(input)

      case ByteValue.DoubleQuote ⇒
        if (!isStartOfEscapeSequence) isStartOfStringExpression = !isStartOfStringExpression
        isStartOfEscapeSequence = false
        buffer ++= ByteString(input)

      case ByteValue.CurlyBraceStart if !isStartOfStringExpression ⇒
        isStartOfEscapeSequence = false
        objectDepthLevel += 1
        buffer ++= ByteString(input)

      case ByteValue.CurlyBraceEnd if !isStartOfStringExpression ⇒
        isStartOfEscapeSequence = false
        objectDepthLevel -= 1
        buffer ++= ByteString(input)
        if (objectDepthLevel == 0)
          completedObjectIndexes :+= buffer.length

      case otherValue if ByteValue.Whitespace.isWhitespace(otherValue) && !isStartOfStringExpression ⇒
      // skip

      case otherValue if isStartOfObject ⇒
        isStartOfEscapeSequence = false
        buffer ++= ByteString(input)

      case _ ⇒
        isValid = false
    }

  private def isStartOfObject: Boolean =
    objectDepthLevel > 0

}