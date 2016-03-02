/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.util.concurrent.ThreadLocalRandom

import akka.http.impl.spi.HttpInstrumentation
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.model.headers.`X-Trace`

final class XTraceHttpInstrumentation extends HttpInstrumentation {

  // --- SERVER ---

  override def onServerRequestStart(req: HttpRequest): HttpRequest = {
    // would be interesting to have "stream context" instead of the thread local hm
    val x = req.header[`X-Trace`]
    x foreach { xtrace ⇒
      XTrace.set(xtrace)
    }
    req
  }

  override def onServerRequestCompleted(req: HttpRequest): Unit = {
    XTrace.clear()
  }

  override def onServerResponseStart(res: HttpResponse, context: AnyRef): HttpResponse = {
    context match {
      case x: `X-Trace` if res.header[`X-Trace`].isEmpty ⇒ res.addHeader(x)
      case _ ⇒ res
    }
  }

  override def onServerCompleteResponse(res: HttpResponse, context: AnyRef): Unit = ()

  // --- CLIENT ---

  override def onClientRequestStart(req: HttpRequest): HttpRequest =
    if (req.header[`X-Trace`].isDefined) req
    else req.addHeader(XTrace.getOrNew())
  override def onClientRequestCompleted(req: HttpRequest): Unit = ()
  override def onClientResponseStart(res: HttpResponse, context: AnyRef): HttpResponse = res
  override def onClientResponseCompleted(res: HttpResponse, context: AnyRef): Unit = ()
}

object HttpSPI {
  // TODO would be atomic with people registering here
  val instance: HttpInstrumentation = new XTraceHttpInstrumentation
}

// TODO move around
object XTrace {

  private val `the astral plane` = new ThreadLocal[`X-Trace`]()

  def set(header: `X-Trace`): `X-Trace` = {
    `the astral plane` set header
    header
  }

  def clear(): Unit =
    `the astral plane`.remove()

  def getOrNew(): `X-Trace` =
    get getOrElse set(`X-Trace`(randomId))

  def get(): Option[`X-Trace`] =
    Option(`the astral plane`.get())

  def attachExisting(maybeTrace: Option[`X-Trace`])(res: HttpResponse): HttpResponse =
    maybeTrace match {
      case Some(x) ⇒ res.addHeader(x)
      case _       ⇒ res
    }

  def attachExistingOrNew(res: HttpResponse): HttpResponse =
    res.addHeader(getOrNew())

  // TODO could include "machine id" (more like a snowflake)
  private def randomId: String =
    Vector.fill(10)(65 + ThreadLocalRandom.current().nextInt(25)).map(_.toChar.toLower).mkString("")

}
