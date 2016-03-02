/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.spi

import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }

abstract class HttpInstrumentation {

  // --- SERVER ---

  /** Server: prepare incoming request */
  def onServerRequestStart(req: HttpRequest): HttpRequest
  /** Server: incoming request has completed streaming request body */
  def onServerRequestCompleted(req: HttpRequest): Unit

  /** Server: prepare outgoing resposne */
  def onServerResponseStart(res: HttpResponse, context: AnyRef): HttpResponse
  /** Server: outgoing response has completed streaming response body */
  def onServerCompleteResponse(res: HttpResponse, context: AnyRef): Unit

  // --- CLIENT ---

  /** Client: prepare outgoing request */
  def onClientRequestStart(req: HttpRequest): HttpRequest
  /** Client: outgoing request completed streaming request body */
  def onClientRequestCompleted(req: HttpRequest): Unit

  /** Client: prepare incoming response */
  def onClientResponseStart(res: HttpResponse, context: AnyRef): HttpResponse
  /** Client: incoming response completed streaming request body */
  def onClientResponseCompleted(res: HttpResponse, context: AnyRef): Unit
}