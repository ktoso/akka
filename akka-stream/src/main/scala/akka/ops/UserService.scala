/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.ops

import akka.stream.scaladsl.Source

import scala.concurrent.Future

case class User(id: Int, name: String)
case class UserForm(name: String)

trait UserService {
  def create(data: UserForm): Future[User] // => POST user

  def show(id: Int): User // GET users/:id
  def find(name: String): Source[User, _] // GET users?name=:name
}

/*

trait MyService {
  def create: Create[UserForm, User]
}

trait MyService extends Restful {
  override def get: Get[Id, User]
  override def create: Create[UserForm, User]
  override def delete: Get[Id, User]
}

trait Op[I, O] {
  private val mat = ???
  private val httpthingy = ???

  def run(in: I): Future[Rep[O]]
}

val authenticator = ??? // Req => Req // stuffs in a token etc
locator.use(authenticator) // or plain conf
locator.use(restStyleWireProtocol) // by default

val userService = locator.serviceOf(classOf[UserService], version = 1).using(auththingy) // or from config
userService.using(binaryAkkaSomethingProtocol).create()

userService.create.run(UserForm("lol")): Future[Rep[User]]
  - pick marshaller
  - http client
  - post the message
    - maybe retry
    - maybe circuit breaker
    - maybe cache
    - maybe auth
  - give Future[Rep] to user?

val get = userService.get.withCache(1.minute).retry(4.times): Op
get.run(12): Future[Rep[User]]

get.adapt(myUserAdapter).run(): Future[Rep[MyUser]]


// composition idea:
user.get.to(twitter.tweetsOf).run(): Op[Int, Source[Tweet]]

user.adapt().get.to(Flow[MyUser, TwitterHandle], twitter.tweetsOf): Op[Int, Source[Tweet]]

user.adapt().get.map(f): Op[Int, Tweet] // meh?

 */

