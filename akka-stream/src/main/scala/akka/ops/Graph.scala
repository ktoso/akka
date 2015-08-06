/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.ops

import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait Exec

trait Req[T]
trait Rep[T] {
  def run()(implicit ex: Exec): T = ???
}

trait Op[I, O] {
  def apply(in: I): Rep[O] = ???

  def to[XX, X <: Rep[XX]](target: Op[I, O])

  def withPattern(p: Pattern): this.type = ???
}
object Op {
  implicit def mapStream[I, O](op: Op[Source[I, Unit], O]) = new {
    def mapStream(f: I => O): Op[_, XX, _, Rep[XX]] = ???
  }
    
}

trait Pattern {}
object CircuitBreaker extends Pattern

trait User {
  def create:

  def activity: Op[String, Source[String,Unit], Req[String], Rep[Source[String, Unit]]]
}

trait Twitter {
  def stream: Op[Source[String, Unit], Source[String, Unit], Req[Source[String, Unit]], Rep[Source[String, Unit]]]
}

trait OpServer {
  def path: String
}

class UserService extends OpServer with User {
  def path = "/user"

  override def activity: Op[String, Source[String, Unit]] =
  in => {
    Source.single("")
  }

}

object testing {
  
  implicit val ex = new Exec {}
  
  val user: User = ??? // locator.serviceOf[User]
  val twitter: Twitter = ??? // locator.serviceOf[Twitter]

  val activityRep: Rep[Source[String, Unit]] = user.activity("")

  val run: Source[String, Unit] = activityRep.run()

  // ----

  val richOp: Op[String, Source[String, Unit], Req[String], Rep[Source[String, Unit]]] =
    user.activity.withPattern(CircuitBreaker)

  val run2 = richOp("").run()

  // ----

  user.activity.mapStream(_.head).to(twitter.stream).run()
}
