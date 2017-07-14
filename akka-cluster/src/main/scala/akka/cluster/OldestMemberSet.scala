/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.cluster

import akka.annotation.DoNotInherit
import akka.cluster.ClusterSettings.DataCenter

import scala.collection.immutable.SortedSet
import scala.util.{ Failure, Success, Try }

/**
 * Encapsulates set of members in the cluster, maintained in [[Member.ageOrdering]] ordering.
 * Note that such ordering only makes sense within one data-center and for nodes which are `Up`.
 */
@DoNotInherit
sealed trait OldestMemberSet {

  def dataCenter: DataCenter
  
  def oldest: Option[Member]
  
  def +(m: Member): Try[OldestMemberSet] 
  def -(m: Member): OldestMemberSet 
}

// specialize for low numbers?
// private[cluster] final class OldestMemberSet1 private(_dc: DataCenter, members: SortedSet[Member]) {

private[cluster] final class OldestMemberSetImpl private(val dataCenter: DataCenter, members: SortedSet[Member]) extends OldestMemberSet {
  
  def oldest = members.headOption
  
  def +(m: Member): Try[OldestMemberSet] =
    m.dataCenter match {
      case `dataCenter` => Failure(new IllegalArgumentException(""))
      case _ => 
        // when adding a Member we must first remove 
        val ms = members.filterNot(_.uniqueAddress == m.uniqueAddress) + m
        Success(new OldestMemberSetImpl(dataCenter, ms))
    }

  def -(m: Member): OldestMemberSet =
    m.dataCenter match {
      case `dataCenter` => this
      case _ => new OldestMemberSetImpl(dataCenter, members - m)
    }
}

object OldestMemberSet {
  private val emptyOrderedSet = SortedSet.empty(Member.ageOrdering)
  
  def apply(dc: DataCenter, members: Set[Member]): OldestMemberSetImpl = {
    
    new OldestMemberSetImpl(dc, emptyOrderedSet ++ members)()
  }
}
