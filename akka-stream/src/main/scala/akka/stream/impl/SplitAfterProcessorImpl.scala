///**
// * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
// */
//package akka.stream.impl
//
//import scala.util.control.NonFatal
//import akka.actor.Props
//import akka.stream.ActorFlowMaterializerSettings
//import akka.stream.Supervision
//import akka.stream.scaladsl.Source
//
///**
// * INTERNAL API
// */
//private[akka] object SplitAfterProcessorImpl {
//  def props(settings: ActorFlowMaterializerSettings, splitPredicate: Any ⇒ Boolean): Props =
//    Props(new SplitAfterProcessorImpl(settings, splitPredicate))
//
//  private trait SplitDecision
//  private case object Split extends SplitDecision
//  private case object Continue extends SplitDecision
//  private case object Drop extends SplitDecision
//}
//
///**
// * INTERNAL API
// */
//private[akka] class SplitAfterProcessorImpl(_settings: ActorFlowMaterializerSettings, val splitPredicate: Any ⇒ Boolean)
//  extends MultiStreamOutputProcessor(_settings) {
//
//  import MultiStreamOutputProcessor._
//  import SplitAfterProcessorImpl._
//
//  val decider = settings.supervisionDecider
//  var currentSubstream: SubstreamOutput = _
//
//  val openSubstream: TransferPhase = TransferPhase(primaryOutputs.NeedsDemand) { () ⇒
//    val substreamOutput = createSubstreamOutput()
//    val substreamFlow = Source(substreamOutput) // substreamOutput is a Publisher
//    primaryOutputs.enqueueOutputElement(substreamFlow)
//    currentSubstream = substreamOutput
//    nextPhase(serveSubstream(currentSubstream))
//  }
//
//  def serveSubstream(substream: SubstreamOutput) = TransferPhase(primaryInputs.NeedsInput && substream.NeedsDemand) { () ⇒
//    val elem = primaryInputs.dequeueInputElement()
//    decideSplit(elem) match {
//      case Continue ⇒
//        substream.enqueueOutputElement(elem)
//
//      case Split ⇒
//        substream.enqueueOutputElement(elem)
//        completeSubstreamOutput(currentSubstream.key)
//        currentSubstream = null
//        nextPhase(openSubstream)
//
//      case Drop ⇒
//      // drop elem and continue
//    }
//  }
//
//  // Ignore elements for a cancelled substream until a new substream needs to be opened
//  val ignoreUntilNewSubstream = TransferPhase(primaryInputs.NeedsInput) { () ⇒
//    val elem = primaryInputs.dequeueInputElement()
//    decideSplit(elem) match {
//      case Continue | Drop ⇒ // ignore elem
//      case Split           ⇒ nextPhase(openSubstream)
//    }
//  }
//
//  private def decideSplit(elem: Any): SplitDecision =
//    try {
//      val predicate = splitPredicate(elem)
//      if (predicate) Split else Continue
//    } catch {
//      case NonFatal(e) if decider(e) != Supervision.Stop ⇒
//        if (settings.debugLogging)
//          log.debug("Dropped element [{}] due to exception from splitAfter function: {}", elem, e.getMessage)
//        Drop
//    }
//
//  nextPhase(openSubstream)
//
//  override def completeSubstreamOutput(substream: SubstreamKey): Unit = {
//    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
//    super.completeSubstreamOutput(substream)
//  }
//
//  override def cancelSubstreamOutput(substream: SubstreamKey): Unit = {
//    if ((currentSubstream ne null) && substream == currentSubstream.key) nextPhase(ignoreUntilNewSubstream)
//    super.cancelSubstreamOutput(substream)
//  }
//
//}
