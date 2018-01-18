/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import java.util.concurrent.CompletionStage

import akka.stream.scaladsl.{ SinkRef, SourceRef }
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.util.OptionVal
import akka.stream.{ SinkShape, scaladsl }

import scala.concurrent.Future

object SinkRef {

  import scala.compat.java8.FutureConverters._

  // TODO this is hell to do since no flatMapMaterialized
  //  def source[T](): Source[T, CompletionStage[>>> the java one SinkRef[T]]] =
  //    scaladsl.SinkRef.source[T]()
  //      .mapMaterializedValue(_.toJava) // still have the scala one
  //      .asJava

  def source[T](): Source[T, CompletionStage[SinkRef[T]]] =
    ???
  //    scaladsl.SinkRef.source[T]()
  //      .mapMaterializedValue(_.toJava)
  //      .asJava

}

abstract class SinkRef[In] extends GraphStageWithMaterializedValue[SinkShape[In], Future[SourceRef[In]]] with Serializable {

  def asScala: scaladsl.SinkRef[In] = this.asInstanceOf[akka.stream.scaladsl.SinkRef[In]]

}
