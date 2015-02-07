/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.impl

import java.nio.ByteBuffer

/**
 * Circular array based buffer.
 */
final case class CircularByteBuffer(capacity: Int) {
  require(capacity > 0, "capacity must be > 0")
  require(isPowerOfTwo(capacity), "capacity must be power of 2")

  val buf = ByteBuffer.allocate(capacity)

  private var writePos = 0
  private var readPos = 0

  def free(): Integer = ???
  def used(): Integer = ???

  def array = buf.array

  def backing = buf

  private def isPowerOfTwo(n: Int): Boolean = (n & (n - 1)) == 0 // FIXME this considers 0 a power of 2
}
