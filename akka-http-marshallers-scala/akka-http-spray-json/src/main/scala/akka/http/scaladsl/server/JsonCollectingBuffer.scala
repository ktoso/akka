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
    if (isValid)
      if (input.length > 1)
        input.foreach(c ⇒ append(ByteString(c)))
      else {
        input.head match {
          case ByteValue.SquareBraceStart if !isStartOfObject ⇒
          // do nothing

          case ByteValue.SquareBraceEnd if !isStartOfObject   ⇒
          // do nothing

          case ByteValue.Comma if !isStartOfObject            ⇒
          // do nothing

          case ByteValue.Backslash ⇒
            isStartOfEscapeSequence = true
            buffer ++= input

          case ByteValue.DoubleQuote ⇒
            if (!isStartOfEscapeSequence) isStartOfStringExpression = !isStartOfStringExpression
            isStartOfEscapeSequence = false
            buffer ++= input

          case ByteValue.CurlyBraceStart if !isStartOfStringExpression ⇒
            isStartOfEscapeSequence = false
            objectDepthLevel += 1
            buffer ++= input

          case ByteValue.CurlyBraceEnd if !isStartOfStringExpression ⇒
            isStartOfEscapeSequence = false
            objectDepthLevel -= 1
            buffer ++= input
            if (objectDepthLevel == 0)
              completedObjectIndexes :+= buffer.length

          case otherValue if ByteValue.Whitespace.isWhitespace(otherValue) && !isStartOfStringExpression ⇒
          // skip

          case otherValue if isStartOfObject ⇒
            isStartOfEscapeSequence = false
            buffer ++= input

          case _ ⇒
            isValid = false
        }
      }

  def pop: BufferPopResult =
    (isValid, completedObjectIndexes.headOption) match {
      case (true, Some(index)) ⇒
        val result = buffer.slice(0, index)

        buffer = buffer.slice(index, buffer.length)
        completedObjectIndexes = completedObjectIndexes.tail.map(_ - index)

        Success(Some(result))

      case (true, _) ⇒
        Success(None)

      case (false, _) ⇒
        Failure(InvalidJson(buffer))
    }

  def valid: Boolean =
    isValid

  private def isStartOfObject: Boolean =
    objectDepthLevel > 0

}