/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote

import akka.actor.DeadLetterSuppression
import akka.annotation.InternalApi
import akka.stream.impl.ReactiveStreamsCompliance

/** INTERNAL API: Protocol messages used by the various stream -ref implementations. */
@InternalApi
private[akka] object StreamRefs {

  @InternalApi
  sealed trait Protocol

  @InternalApi
  final case class SequencedOnNext[T](seqNr: Long, payload: T) extends StreamRefs.Protocol {
    if (payload == null) throw ReactiveStreamsCompliance.elementMustNotBeNullException
  }

  /** Sent to a the receiver side of a SinkRef, once the sending side of the SinkRef gets signalled a Failure. */
  @InternalApi
  final case class RemoteSinkFailure(msg: String) extends StreamRefs.Protocol

  /** Sent to a the receiver side of a SinkRef, once the sending side of the SinkRef gets signalled a completion. */
  @InternalApi
  final case class RemoteSinkCompleted(seqNr: Long) extends StreamRefs.Protocol

  /**
   * Cumulative demand, equivalent to sequence numbering all events in a stream. *
   * This message may be re-delivered.
   */
  @InternalApi
  final case class CumulativeDemand(seqNr: Long) extends StreamRefs.Protocol with DeadLetterSuppression {
    if (seqNr <= 0) throw ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException
  }

  // --- exceptions ---

  final case class RemoteStreamRefActorTerminatedException(msg: String) extends RuntimeException(msg)
  final case class InvalidSequenceNumberException(expectedSeqNr: Long, gotSeqNr: Long, msg: String)
    extends IllegalStateException(s"$msg (expected: $expectedSeqNr, got: $gotSeqNr)")

}

