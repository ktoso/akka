/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.impl.SplitDecision.SplitDecision
import akka.stream.scaladsl.Source
import akka.stream.{ ActorFlowMaterializerSettings, Supervision }

import scala.util.control.NonFatal

object SplitDecision {
  sealed abstract class SplitDecision

  /** Splits before the current element. The current element will be the first element in the new substream. */
  case object SplitBefore extends SplitDecision

  /** Splits after the current element. The current element will be the last element in the current substream. */
  case object SplitAfter extends SplitDecision

  /** Drops the current element, completes the current substream. The next element will be emitted into a new substream. */
  case object SplitDrop extends SplitDecision

  /** Emit this element into the current substream. */
  case object Continue extends SplitDecision

  /** Drop this element without signalling it to any substream. */
  case object Drop extends SplitDecision
}

/**
 * INTERNAL API
 */
private[akka] object SplitWhereProcessorImpl {
  def props(settings: ActorFlowMaterializerSettings, splitPredicate: Any ⇒ SplitDecision): Props =
    Props(new SplitWhereProcessorImpl(settings, in ⇒ splitPredicate(in)))
}

/**
 * INTERNAL API
 */
private[akka] class SplitWhereProcessorImpl(_settings: ActorFlowMaterializerSettings, val splitPredicate: Any ⇒ SplitDecision)
  extends MultiStreamOutputProcessor(_settings) {

  import MultiStreamOutputProcessor._
  import SplitDecision._

  val decider = settings.supervisionDecider
  var currentSubstream: SubstreamOutput = _

  def openSubstream(andThen: SubstreamOutput ⇒ TransferPhase): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    val substreamOutput = createSubstreamOutput()
    val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
    primaryOutputs.enqueueOutputElement(substreamFlow)
    currentSubstream = substreamOutput

    nextPhase(andThen(currentSubstream))
  }

  // Serving the substream is split into two phases to minimize elements "held in hand"
  def serveSubstreamFirst(substream: SubstreamOutput, elem: Any) = TransferPhase(substream.NeedsDemand) { () ⇒
    substream.enqueueOutputElement(elem)
    nextPhase(serveSubstreamRest(substream))
  }

  // Note that this phase is allocated only once per _slice_ and not per element
  def serveSubstreamRest(substream: SubstreamOutput): TransferPhase = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue ⇒ substream.enqueueOutputElement(elem)
      case SplitBefore ⇒
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(serveSubstreamFirst(_, elem)))
      case SplitAfter ⇒
        substream.enqueueOutputElement(elem)
        completeSubstreamOutput(currentSubstream.key)
        currentSubstream = null
        nextPhase(openSubstream(serveSubstreamRest))
      case SplitDrop ⇒
        completeSubstreamOutput(currentSubstream.key)
        nextPhase(openSubstream(serveSubstreamRest))
      case Drop ⇒ // drop elem and continue
    }
  }

  // Ignore elements for a cancelled substream until a new substream needs to be opened
  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    val elem = primaryInputs.dequeueInputElement()
    decideSplit(elem) match {
      case Continue | Drop        ⇒ // ignore elem
      case SplitBefore            ⇒ nextPhase(openSubstream(serveSubstreamFirst(_, elem)))
      case SplitAfter | SplitDrop ⇒ nextPhase(openSubstream(serveSubstreamRest))
    }
  }

  private def decideSplit(elem: Any): SplitDecision =
    try splitPredicate(elem) catch {
      case NonFatal(e) if decider(e) != Supervision.Stop ⇒
        if (settings.debugLogging)
          log.debug("Dropped element [{}] due to exception from splitWhen function: {}", elem, e.getMessage)
        Drop
    }

  nextPhase(openSubstream(serveSubstreamRest))

  override def completeSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.completeSubstreamOutput(substream)
  }

  override def cancelSubstreamOutput(substream: SubstreamKey): Unit = {
    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.cancelSubstreamOutput(substream)
  }

}
