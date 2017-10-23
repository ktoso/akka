/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import akka.annotation.InternalApi
import akka.stream.impl.ReactiveStreamsCompliance

object StreamRefs {

  @InternalApi
  sealed trait Protocol

  @InternalApi
  final case class Sequenced[T](seqNr: Long, payload: T) extends StreamRefs.Protocol

  @InternalApi
  final case class Demand[T](demand: Long) extends StreamRefs.Protocol {
    if (demand <= 0) throw ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException
  }
}

final case class RemoteStreamRefActorTerminatedException(msg: String) extends RuntimeException(msg)
