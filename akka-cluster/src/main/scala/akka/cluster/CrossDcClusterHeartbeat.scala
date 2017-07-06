/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.cluster

import akka.actor.{ Actor, ActorLogging, ActorSelection, Address, NoSerializationVerificationNeeded, RootActorPath }
import akka.annotation.InternalApi
import akka.cluster.ClusterEvent._
import akka.event.Logging
import akka.remote.FailureDetectorRegistry
import akka.util.ConstantFun

import scala.collection.{ SortedSet, immutable }

/**
 * INTERNAL API
 *
 * This actor is will be started on all nodes participating in a cluster,
 * however unlike the within-dc heartbeat sender ([[ClusterHeartbeatSender]]),
 * it will only actively work on `n` "oldest" nodes of a given data center.
 *
 * It will monitor it's oldest counterparts in other data centers.
 * For example, a DC configured to have (up to) 4 monitoring actors,
 * will have 4 such active at any point in time, and those will monitor
 * the (at most) 4 oldest nodes within other data centers.
 *
 * This monitoring mode is both simple and predictable, and also uses the assumption that
 * "nodes which stay around for a long time, become old", and those rarely change. In a way,
 * they are the "core" of a cluster, while other nodes may be very dynamically changing worked
 * nodes which aggresively come and go as the traffic in the service changes.
 */
private[cluster] final class CrossDcHeartbeatSender() extends Actor with ActorLogging {
  import CrossDcHeartbeatSender._

  val cluster = Cluster(context.system)
  val verboseHeartbeat = cluster.settings.Debug.VerboseHeartbeatLogging
  import cluster.settings._
  import cluster.{ scheduler, selfAddress, selfDataCenter, selfUniqueAddress }
  import context.dispatcher

  // For inspecting if in active state; allows avoiding "becoming active" when already active
  var activelyMonitoring = false

  val filterExternalMembers: Member ⇒ Boolean =
    member ⇒ member.dataCenter != cluster.selfDataCenter

  val crossDcSettings: cluster.settings.CrossDcFailureDetectorSettings = cluster.settings.CrossDcFailureDetectorSettings
  val crossDcFailureDetector = cluster.crossDcFailureDetector

  val selfHeartbeat = ClusterHeartbeatSender.Heartbeat(selfAddress)

  var dataCentersState: CrossDcHeartbeatingState = CrossDcHeartbeatingState.init(
    crossDcFailureDetector,
    crossDcSettings.NrOfMonitoringActors,
    SortedSet.empty
  )

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask = scheduler.schedule(
    PeriodicTasksInitialDelay max HeartbeatInterval,
    HeartbeatInterval, self, ClusterHeartbeatSender.HeartbeatTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
    if (verboseHeartbeat) log.debug("Initialized cross-dc heartbeat sender as DORMANT in DC: [{}]", selfDataCenter)
  }

  override def postStop(): Unit = {
    dataCentersState.allMembers.foreach(a ⇒ crossDcFailureDetector.remove(a.address))
    heartbeatTask.cancel()
    cluster.unsubscribe(self)
  }

  /**
   * Looks up and returns the remote cluster heartbeat connection for the specific address.
   */
  def heartbeatReceiver(address: Address): ActorSelection =
    context.actorSelection(RootActorPath(address) / "system" / "cluster" / "heartbeatReceiver")

  def receive: Actor.Receive =
    dormant orElse introspecting

  /**
   * In this state no cross-datacenter heartbeats are sent by this actor.
   * This may be because one of those reasons:
   *   - no nodes in other DCs were detected yet
   *   - nodes in other DCs are present, but this node is not tht n-th oldest in this DC (see
   *     `number-of-cross-datacenter-monitoring-actors`), so it does not have to monitor that other data centers
   *
   * In this state it will however listen to cluster events to eventually take over monitoring other DCs
   * in case it becomes "old enough".
   */
  def dormant: Actor.Receive = {
    case s: CurrentClusterState               ⇒ init(s)
    case MemberRemoved(m, _)                  ⇒ removeMember(m)
    case evt: MemberEvent                     ⇒ addMember(evt.member)
    case ClusterHeartbeatSender.HeartbeatTick ⇒ // ignore...
  }

  def active: Actor.Receive = {
    case ClusterHeartbeatSender.HeartbeatTick                ⇒ heartbeat()
    case ClusterHeartbeatSender.HeartbeatRsp(from)           ⇒ heartbeatRsp(from)
    case MemberRemoved(m, _)                                 ⇒ removeMember(m)
    case evt: MemberEvent                                    ⇒ addMember(evt.member)
    case ClusterHeartbeatSender.ExpectedFirstHeartbeat(from) ⇒ triggerFirstHeartbeat(from)
  }

  def introspecting: Actor.Receive = {
    case ReportStatus() ⇒
      sender() ! {
        if (activelyMonitoring) CrossDcHeartbeatSender.MonitoringActive(dataCentersState)
        else CrossDcHeartbeatSender.MonitoringDormant()
      }
  }

  def init(snapshot: CurrentClusterState): Unit = {
    // val unreachable = snapshot.unreachable.collect({ case m if filterExternalMembers(m) => m.uniqueAddress })
    // nr of monitored nodes is the same as the number of monitoring nodes (`n` oldest in one DC watch `n` oldest in other)
    val nodes = snapshot.members
    val nrOfMonitoredModes = crossDcSettings.NrOfMonitoringActors
    dataCentersState = CrossDcHeartbeatingState.init(crossDcFailureDetector, nrOfMonitoredModes, nodes)

    log.info("Snapshot nodes: " + snapshot.members)
    snapshot.members.foreach(dataCentersState.addMember)
  }

  def addMember(m: Member): Unit =
    if (m.status != MemberStatus.Joining && m.status != MemberStatus.WeaklyUp) {
      // since we only monitor nodes in Up or later states, due to the n-th oldest requirement
      dataCentersState = dataCentersState.addMember(m)
      if (verboseHeartbeat) log.debug("Register member {} for cross DC heartbeat (will only heartbeat if oldest)", m)

      becomeActiveIfResponsibleForHeartbeat()
    }

  def removeMember(m: Member): Unit =
    if (filterExternalMembers(m)) { // we only ever deal with external dc members
      if (m.uniqueAddress == cluster.selfUniqueAddress) {
        // This cluster node will be shutdown, but stop this actor immediately
        // to avoid further updates
        context stop self
      } else {
        dataCentersState = dataCentersState.removeMember(m)

        // if we have no more nodes to monitor in given DC, we can revert to `dormant` state: 
        becomeDormantIfPossible()
      }
    }

  def heartbeat(): Unit = {
    dataCentersState.activeReceivers foreach { to ⇒
      if (crossDcFailureDetector.isMonitoring(to.address)) {
        if (verboseHeartbeat) log.debug("Cluster Node [{}][{}] - (Cross) Heartbeat to [{}]", selfDataCenter, selfAddress, to.address)
      } else {
        if (verboseHeartbeat) log.debug("Cluster Node [{}][{}] - First (Cross) Heartbeat to [{}]", selfDataCenter, selfAddress, to.address)
        // schedule the expected first heartbeat for later, which will give the
        // other side a chance to reply, and also trigger some resends if needed
        scheduler.scheduleOnce(HeartbeatExpectedResponseAfter, self, ClusterHeartbeatSender.ExpectedFirstHeartbeat(to))
      }
      heartbeatReceiver(to.address) ! selfHeartbeat
    }
  }

  def heartbeatRsp(from: UniqueAddress): Unit = {
    if (verboseHeartbeat) log.debug("Cluster Node [{}][{}] - (Cross) Heartbeat response from [{}]", selfDataCenter, selfAddress, from.address)
    dataCentersState = dataCentersState.heartbeatRsp(from)
  }

  def triggerFirstHeartbeat(from: UniqueAddress): Unit =
    if (dataCentersState.contains(from) && !crossDcFailureDetector.isMonitoring(from.address)) {
      if (verboseHeartbeat) log.debug("Cluster Node [{}][{}] - Trigger extra expected (cross) heartbeat from [{}]", selfAddress, from.address)
      crossDcFailureDetector.heartbeat(from.address)
    }

  private def selfIsResponsibleForCrossDcHeartbeat(): Boolean =
    {
      val activeDcs: Int = dataCentersState.dataCenters.size
      val moreThanOneDcActive = activeDcs > 1

      val res = cluster.state.members
        .find(_.uniqueAddress == selfUniqueAddress)
      res.exists(_.status == MemberStatus.Up)

      val shouldBeActive = dataCentersState.shouldActivelyMonitorNodes(selfDataCenter, selfUniqueAddress)
      moreThanOneDcActive && shouldBeActive
    }

  /** Idempotent, become active if this node is n-th oldest and should monitor other nodes */
  private def becomeActiveIfResponsibleForHeartbeat(): Unit = {
    if (!activelyMonitoring && selfIsResponsibleForCrossDcHeartbeat()) {
      log.info("Becoming ACTIVE in {}, monitoring nodes: {}", selfDataCenter, dataCentersState)
      activelyMonitoring = true

      context.become(active orElse introspecting)
    } else if (!activelyMonitoring)
      log.info("Remaining DORMANT; others in {} handle monitoring for me ({})", selfDataCenter, dataCentersState.state.getOrElse(cluster.selfDataCenter, Set.empty).map(m ⇒ m.address.port.get + "@" + m.status).mkString(","))
  }

  /** Idempotent, become or remain dormant if this node does not have to fulful the role of actively monitoring */
  private def becomeDormantIfPossible(): Unit = {
    if (!selfIsResponsibleForCrossDcHeartbeat()) {
      log.info("Becoming DORMANT in {}, all known nodes: {}", selfDataCenter)
      activelyMonitoring = false
      context.become(dormant orElse introspecting)
    }
  }

}

/** INTERNAL API */
@InternalApi
private[akka] object CrossDcHeartbeatSender {

  // -- messages intended only for local messaging during testing -- 
  sealed trait InspectionCommand extends NoSerializationVerificationNeeded
  final case class ReportStatus()

  sealed trait StatusReport extends NoSerializationVerificationNeeded
  sealed trait MonitoringStateReport extends StatusReport
  final case class MonitoringActive(state: CrossDcHeartbeatingState) extends MonitoringStateReport
  final case class MonitoringDormant() extends MonitoringStateReport
  // -- end of messages intended only for local messaging during testing -- 
}

/** INTERNAL API */
@InternalApi
private[cluster] final case class CrossDcHeartbeatingState(
  failureDetector:         FailureDetectorRegistry[Address],
  nrOfMonitoredNodesPerDc: Int,
  state:                   Map[ClusterSettings.DataCenter, SortedSet[Member]]
) {
  import CrossDcHeartbeatingState._

  // TODO should we optimise this to know about DC of it?
  def contains(address: UniqueAddress): Boolean =
    state.exists(_._2.exists(_.uniqueAddress == address))

  def contains(member: Member): Boolean = {
    val dc = member.dataCenter
    val memberUniqueAddress = member.uniqueAddress

    // slight optimisation, to avoid searching over all members:
    state.get(dc) match {
      case Some(dcMembers) ⇒ dcMembers.exists(_.uniqueAddress == memberUniqueAddress)
      case _               ⇒ false // since we don't have any entry for this DC, no member will match
    }
  }

  /**
   * Decides if `self` node should become active and monitor other nodes with heartbeats.
   * Only the `nrOfMonitoredNodesPerDc`-oldest nodes in each DC fulfil this role.
   */
  def shouldActivelyMonitorNodes(selfDc: ClusterSettings.DataCenter, selfAddress: UniqueAddress): Boolean = {
    /** Since we need ordering of oldests guaranteed, we must only look at Up (or Leaving, Exiting...) nodes */
    def atLeastUp(m: Member): Boolean =
      m.status != MemberStatus.WeaklyUp && m.status != MemberStatus.Joining

    val selfDcNeighbours: SortedSet[Member] = state.getOrElse(
      selfDc,
      throw new IllegalStateException(s"Self DataCenter was [$selfDc] however no such entries in ${Logging.simpleName(getClass)} present. " +
        s"This is very likely a bug, please turn on debug logging and open an issue about it."))
    val selfDcOldOnes = selfDcNeighbours.filter(atLeastUp).take(nrOfMonitoredNodesPerDc)

    // if this node is part of the "n oldest nodes" it should indeed monitor other nodes:
    val shouldMonitorActively = selfDcOldOnes.exists(_.uniqueAddress == selfAddress)
    shouldMonitorActively
  }

  def addMember(m: Member): CrossDcHeartbeatingState = {
    val dc = m.dataCenter
    def addMember0(member: Member): CrossDcHeartbeatingState = {
      val updatedMembers = state.getOrElse(dc, emptyMembersSortedSet) + m
      this.copy(state = state.updated(dc, updatedMembers)
      )
    }

    // we need to remove the member first, to avoid having "duplicates"
    // this is because the removal and uniqueness we need is only by uniqueAddress
    // which is not the equals defined on Member; so a "replace" (which this op might be),
    // must be a "remove then add".
    removeMember(m)
    addMember0(m)
  }

  def removeMember(m: Member): CrossDcHeartbeatingState = {
    val dc = m.dataCenter
    state.get(dc) match {
      case Some(dcMembers) ⇒
        val updatedMembers = dcMembers.filterNot(_.uniqueAddress == m.uniqueAddress)
        copy(state = state.updated(dc, updatedMembers))
      case None ⇒
        this // no change needed, was certainly not present (not even its DC was) 
    }
  }

  def activeReceivers: Iterable[UniqueAddress] =
    for {
      dc ← state.keys
      member ← state(dc).take(nrOfMonitoredNodesPerDc)
    } yield member.uniqueAddress

  def allMembers: Iterable[Member] =
    state.values.flatMap(ConstantFun.scalaIdentityFunction)

  def heartbeatRsp(from: UniqueAddress): CrossDcHeartbeatingState =
    if (contains(from)) {
      failureDetector heartbeat from.address
      this
    } else this

  def dataCenters: immutable.Set[String] =
    state.keys.toSet

}
object CrossDcHeartbeatingState {

  /** Sorted by age */
  private def emptyMembersSortedSet: SortedSet[Member] = SortedSet.empty[Member](Member.ageOrdering)

  def init(
    crossDcFailureDetector:  FailureDetectorRegistry[Address],
    nrOfMonitoredNodesPerDc: Int,
    members:                 SortedSet[Member]): CrossDcHeartbeatingState = {
    CrossDcHeartbeatingState(
      crossDcFailureDetector,
      nrOfMonitoredNodesPerDc,
      state = {
      val groupedByDc = members.groupBy(_.dataCenter)

      if (members.ordering == Member.ageOrdering) {
        // we already have the right ordering
        groupedByDc
      } else {
        // we need to enforce the ageOrdering for the SortedSet in each DC
        groupedByDc.map {
          case (dc, ms) ⇒
            dc → (SortedSet.empty[Member](Member.ageOrdering) union ms)
        }
      }
    })
  }

}
