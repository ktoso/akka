/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ExtendedActorSystem
import scala.reflect.runtime.universe._
import akka.actor.Props
import scala.reflect.ClassTag
import scala.reflect.runtime.universe

object ChannelExt extends ExtensionKey[ChannelExtension]

class ChannelExtension(system: ExtendedActorSystem) extends Extension {
  def actorOf[Ch <: ChannelList: TypeTag](factory: ⇒ Channels[_, Ch]): ChannelRef[Ch] =
    new ChannelRef[Ch](system.actorOf(Props(factory)))
}
