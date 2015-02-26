/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi

import akka.http.server.japi.directives._
import scala.collection.immutable
import scala.annotation.varargs
import akka.http.model.japi.HttpMethods

// FIXME: add support for the remaining directives, see #16436
abstract class AllDirectives extends PathDirectives

/**
 *
 */
object Directives extends AllDirectives {
  /**
   * INTERNAL API
   */
  private[japi] def custom(f: immutable.Seq[Route] ⇒ Route): Directive =
    new AbstractDirective {
      def createRoute(first: Route, others: Array[Route]): Route = f(first +: others.toVector)
    }
}
