/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.typed.stream.scaladsl

import akka.stream.scaladsl._
import akka.typed._
import akka.NotUsed

object TypedSink {

  import akka.typed.scaladsl.adapter._

  object Implicits {
    implicit final class TypedEnrichedSink(s: Source.type) {
      def actorRef[T](ref: ActorRef[T], onCompleteMessage: T, onFailureMessage: Throwable ⇒ T): Sink[T, NotUsed] =
        TypedSink.actorRef(ref, onCompleteMessage, onFailureMessage)
    }

    def actorRefWithAck[T](
      ref:               ActorRef[T],
      onInitMessage:     T,
      ackMessage:        T,
      onCompleteMessage: T,
      onFailureMessage:  (Throwable) ⇒ T): Sink[T, NotUsed] =
      TypedSink.actorRefWithAck(ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage)
  }

  /**
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure a the throwable that was signaled
   * to the stream is adapted to the Actors protocol using `onFailureMessage` and
   * then then sent to the destination actor.
   *
   * It will request at most `maxInputBufferSize` number of elements from
   * upstream, but there is no back-pressure signal from the destination actor,
   * i.e. if the actor is not consuming the messages fast enough the mailbox
   * of the actor will grow. For potentially slow consumer actors it is recommended
   * to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   * limiting stage in front of this `Sink`.
   */
  def actorRef[T](ref: ActorRef[T], onCompleteMessage: T, onFailureMessage: Throwable ⇒ T): Sink[T, NotUsed] =
    Sink.actorRef(ref.toUntyped, onCompleteMessage)

  /**
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is always `onInitMessage`, then stream is waiting for acknowledgement message
   * `ackMessage` from the given actor which means that it is ready to process
   * elements. It also requires `ackMessage` message after each stream element
   * to make backpressure work.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * function will be sent to the destination actor.
   */
  def actorRefWithAck[T](
    ref:               ActorRef[T],
    onInitMessage:     T,
    ackMessage:        T,
    onCompleteMessage: T,
    onFailureMessage:  (Throwable) ⇒ T): Sink[T, NotUsed] =
    Sink.actorRefWithAck(ref.toUntyped, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage)
}