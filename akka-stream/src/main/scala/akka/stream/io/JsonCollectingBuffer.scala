/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.util.ByteString

import scala.util.{ Failure, Success, Try }

object JsonCollectingBuffer {

  final val SquareBraceStart = "[".getBytes.head
  final val SquareBraceEnd = "]".getBytes.head
  final val CurlyBraceStart = "{".getBytes.head
  final val CurlyBraceEnd = "}".getBytes.head
  final val DoubleQuote = "\"".getBytes.head
  final val Backslash = "\\".getBytes.head
  final val Comma = ",".getBytes.head

  final val LineBreak = "\n".getBytes.head
  final val LineBreak2 = "\r".getBytes.head
  final val Tab = "\t".getBytes.head
  final val Space = " ".getBytes.head

  final val Whitespace = Set(LineBreak, LineBreak2, Tab, Space)

  def isWhitespace(input: Byte): Boolean =
    Whitespace.contains(input)

  // TODO merge those two
  final case class InvalidJsonException(value: ByteString) extends RuntimeException

  // offendingFrame kept for in-code inspection, not automatically printing it (could contain sensitive data?)
  final case class JsonObjectTooLargeException(maximumObjectLength: Long, offendingFrame: ByteString)
    extends RuntimeException(s"""Json element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

}

/**
 * **Mutable** container of [[ByteString]] which can emit a valid JSON.
 */
// FIXME needs some performance work; it can be simplified to only count the numbers of { and } (in one Int)
// TODO then a proposed rename - JsonFrameCounting?
// TODO remove default value of maxObjectLength
class JsonCollectingBuffer(maximumObjectLength: Int = Int.MaxValue) {
  import JsonCollectingBuffer._

  private var buffer: ByteString = ByteString.empty
  private var completedObjectIndexes: Seq[Int] = Seq.empty // TODO no need for lookahead (just one Int)

  private var charsInObject = 0
  private var isValid = true
  private var isStartOfStringExpression = false
  private var isStartOfEscapeSequence = false
  private var objectDepthLevel = 0

  def append(input: ByteString): Unit =
    if (isValid && input.nonEmpty) {
      var idx = 0
      val length = input.length
      while (idx < length) {
        if (charsInObject > maximumObjectLength) throw new JsonObjectTooLargeException(maximumObjectLength, buffer)
        appendByte(input(idx)) // TODO optimise
        charsInObject += 1
        idx += 1
      }
    }

  def poll(): Try[Option[ByteString]] =
    if (isValid) {
      val possibleResult = completedObjectIndexes.headOption
      if (possibleResult.isDefined) {
        val index = possibleResult.get
        val (result, remainder) = buffer.splitAt(index)
        buffer = remainder
        completedObjectIndexes = completedObjectIndexes.tail.map(_ - index)
        Success(Some(result))
      } else
        Success(None)
    } else
      Failure(InvalidJsonException(buffer))

  def valid: Boolean =
    isValid

  // TODO optimise, we don't want to change anything in the incoming byte string, instead just find the index where the thing ends.
  private def appendByte(input: Byte): Unit =
    if (input == SquareBraceStart && !isStartOfObject) {
      // do nothing
    } else if (input == SquareBraceEnd && !isStartOfObject) {
      // do nothing
    } else if (input == Comma && !isStartOfObject) {
      // do nothing
    } else if (input == Backslash) {
      isStartOfEscapeSequence = true
      buffer :+= input
    } else if (input == DoubleQuote) {
      if (!isStartOfEscapeSequence) isStartOfStringExpression = !isStartOfStringExpression
      isStartOfEscapeSequence = false
      buffer :+= input
    } else if (input == CurlyBraceStart && !isStartOfStringExpression) {
      isStartOfEscapeSequence = false
      objectDepthLevel += 1
      buffer :+= input
    } else if (input == CurlyBraceEnd && !isStartOfStringExpression) {
      isStartOfEscapeSequence = false
      objectDepthLevel -= 1
      buffer :+= input
      if (objectDepthLevel == 0) {
        charsInObject = 0
        completedObjectIndexes :+= buffer.length
      }
    } else if (isWhitespace(input) && !isStartOfStringExpression) {
      if (objectDepthLevel > 0) {
        buffer :+= input
      }
    } else if (isStartOfObject) {
      isStartOfEscapeSequence = false
      buffer :+= input
    } else {
      isValid = false
    }

  private def isStartOfObject: Boolean =
    objectDepthLevel > 0

}