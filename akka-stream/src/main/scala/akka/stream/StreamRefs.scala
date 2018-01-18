package akka.stream

import scala.language.implicitConversions

import akka.NotUsed
import akka.actor.{ ActorRef, DeadLetterSuppression }
import akka.annotation.InternalApi
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.RemoteStreamSenderStage
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.RemoteStreamReceiverStage
import akka.util.OptionVal

import scala.concurrent.Future

object SinkRef {
  // TODO Implement using TCP
  // steps:
  // - lazily, but once bind a port
  // - advertise to other side that they may send data into this port
  // -- "passive mode" ftp ;-)
  // def bulkTransferSource(port: Int = -1): Source[ByteString, SinkRef[ByteString]] = ???

  implicit def autoDeref[T](sinkRef: SinkRef[T]): Sink[T, NotUsed] = sinkRef.sink
}

/**
 * The dual of SourceRef.
 *
 * This is the "handed out" side of a SinkRef. It powers a Source on the other side.
 *
 * Do not create this instance directly, but use `SinkRef` factories, to run/setup its targetRef.
 *
 * We do not materialize the refs back and forth, which is why the 2nd param.
 */
trait SinkRef[In] {
  def sink: Sink[In, NotUsed]
  def getSink: javadsl.Sink[In, NotUsed]
}

/**
 * A SourceRef allows sharing a "reference" with others, with the main purpose of crossing a network boundary.
 * Usually obtaining a SourceRef would be done via Actor messaging, in which one system asks a remote one,
 * to share some data with it, and the remote one decides to do so in a back-pressured streaming fashion -- using a stream ref.
 *
 * See also [[akka.stream.scaladsl.SinkRef]] which is the dual of a SourceRef.
 *
 * To create a SourceRef you have to materialize the Source that you want to get the reference to,
 * and run it `to` the `SourceRef.sink`. A such obtained `SourceRef` can be then shared with a remote host.
 * Once materialized, it will start pulling data from the source you originally materialized to obtain the source reference.
 *
 * For additional configuration see `reference.conf` as well as [[akka.stream.StreamRefAttributes]].
 */
object SourceRef {
  // TODO Implement using TCP
  // def bulkTransfer[T](): Graph[SinkShape[ByteString], SourceRef[ByteString]] = ???

  /** Implicitly converts a SourceRef to a Source. The same can be achieved by calling `.source` on the SourceRef itself. */
  implicit def convertRefToSource[T](ref: SourceRef[T]): Source[T, NotUsed] =
    ref.source
}

/**
 * This stage can only handle a single "sender" (it does not merge values);
 * The first that pushes is assumed the one we are to trust.
 */
trait SourceRef[T] {
  def source: Source[T, NotUsed]
  def getSource: javadsl.Source[T, NotUsed]
}

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.SourceRef]] and [[akka.stream.scaladsl.SinkRef]] directly to obtain stream refs.
 */
@InternalApi
private[akka] object StreamRefs {

  @InternalApi
  sealed trait Protocol

  /**
   * Sequenced `Subscriber#onNext` equivalent.
   * The receiving end of these messages MUST fail the stream if it observes gaps in the sequence,
   * as these messages will not be re-delivered.
   *
   * Sequence numbers start from `0`.
   */
  @InternalApi
  final case class SequencedOnNext[T](seqNr: Long, payload: T) extends StreamRefs.Protocol {
    if (payload == null) throw ReactiveStreamsCompliance.elementMustNotBeNullException
  }

  final case class OnSubscribeHandshake(targetRef: ActorRef) extends StreamRefs.Protocol

  /** Sent to a the receiver side of a stream ref, once the sending side of the SinkRef gets signalled a Failure. */
  @InternalApi
  final case class RemoteStreamFailure(msg: String) extends StreamRefs.Protocol

  /** Sent to a the receiver side of a stream ref, once the sending side of the SinkRef gets signalled a completion. */
  @InternalApi
  final case class RemoteStreamCompleted(seqNr: Long) extends StreamRefs.Protocol

  /**
   * Cumulative demand, equivalent to sequence numbering all events in a stream. *
   * This message may be re-delivered.
   */
  @InternalApi
  final case class CumulativeDemand(seqNr: Long) extends StreamRefs.Protocol with DeadLetterSuppression {
    if (seqNr <= 0) throw ReactiveStreamsCompliance.numberOfElementsInRequestMustBePositiveException
  }

  // --- exceptions ---

  final case class TargetRefNotInitializedYetException()
    extends IllegalStateException("Internal remote target actor ref not yet resolved, yet attempted to send messages to it. This should not happen due to proper flow-control, please open a ticket on the issue tracker: https://github.com/akka/akka")

  final case class StreamRefSubscriptionTimeoutException(msg: String)
    extends IllegalStateException(msg)

  final case class RemoteStreamRefActorTerminatedException(msg: String) extends RuntimeException(msg)
  final case class InvalidSequenceNumberException(expectedSeqNr: Long, gotSeqNr: Long, msg: String)
    extends IllegalStateException(s"$msg (expected: $expectedSeqNr, got: $gotSeqNr)")

  /**
   * Stream refs establish a connection between a local and remote actor, representing the origin and remote sides
   * of a stream. Each such actor refers to the other side as its "partner". We make sure that no other actor than
   * the initial partner can send demand/messages to the other side accidentally.
   *
   * This exception is thrown when a message is recived from a non-partner actor,
   * which could mean a bug or some actively malicient behaviour from the other side.
   *
   * This is not meant as a security feature, but rather as plain sanity-check.
   */
  final case class InvalidPartnerActorException(expectedRef: ActorRef, gotRef: ActorRef, msg: String)
    extends IllegalStateException(s"$msg (expected: $expectedRef, got: $gotRef)")

}
