/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.ActorRef
import akka.stream.MaterializerSettings

/**
 * INTERNAL API
 */
private[akka] class SplitWhenProcessorImpl(_settings: MaterializerSettings, val splitPredicate: Any ⇒ Boolean)
  extends MultiStreamOutputProcessor(_settings) {

  var currentSubstream: SubstreamOutputs = _

  val waitFirst = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    println("waitFirst = ")
    nextPhase(openSubstream(primaryInputs.dequeueInputElement()))
  }

  def openSubstream(elem: Any): TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
    println("openStream(elem) = " + elem)
    val substreamOutput = newSubstream()
    primaryOutputs.enqueueOutputElement(substreamOutput.processor)
    currentSubstream = substreamOutput
    nextPhase(serveSubstreamFirst(currentSubstream, elem))
  }

  // Serving the substream is split into two phases to minimize elements "held in hand"
  def serveSubstreamFirst(substream: SubstreamOutputs, elem: Any) = TransferPhase(substream.NeedsDemand) { () ⇒
    println("serveSubstreamFirst() = " + elem)
    substream.enqueueOutputElement(elem)
    nextPhase(serveSubstreamRest(substream))
  }

  // Note that this phase is allocated only once per _slice_ and not per element
  def serveSubstreamRest(substream: SubstreamOutputs) = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
    println("serveSubstreamRest = " + substream)
    val elem = primaryInputs.dequeueInputElement()
    if (splitPredicate(elem)) {
      currentSubstream.complete()
      currentSubstream = null
      nextPhase(openSubstream(elem))
    } else substream.enqueueOutputElement(elem)
  }

  // Ignore elements for a cancelled substream until a new substream needs to be opened
  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput) { () ⇒
    println("ignoreUntilNewSubstream = ")
    val elem = primaryInputs.dequeueInputElement()
    if (splitPredicate(elem)) nextPhase(openSubstream(elem))
  }

  nextPhase(waitFirst)

  override def invalidateSubstream(substream: ActorRef): Unit = {
    val key = childToKey(substream)
    println("invalidateSubstream() = " + substream + ", key = " + key)
    if ((currentSubstream ne null) && key == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
    super.invalidateSubstream(substream)
  }

}
