/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import java.util.concurrent.ThreadLocalRandom

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.`X-Trace`

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
