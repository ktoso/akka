/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.ws

import akka.http.model.headers.CustomHeader
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow

private[http] case class UpgradeToWebsocketResponseHeader(handlerFlow: Flow[FrameEvent, FrameEvent, Any])(implicit val mat: FlowMaterializer)
  extends InternalCustomHeader("UpgradeToWebsocketResponseHeader") {
}

private[http] abstract class InternalCustomHeader(val name: String) extends CustomHeader {
  override def suppressRendering: Boolean = true

  def value(): String = ""
}