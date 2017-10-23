/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.FlowOps
import akka.util.ByteString

abstract class SourceRef[Out](private[akka] val sourceDriverRef: ActorRef) extends FlowOps[Out, NotUsed] {
}

object SourceRef {
  def apply[T](): Graph[SinkShape[T], SourceRef[T]] = ???
  def merge[T](): Graph[SinkShape[T], SourceRef[T]] = ???
  def bulkTransfer[T](): Graph[SinkShape[ByteString], SourceRef[ByteString]] = ???
}
