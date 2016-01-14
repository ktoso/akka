/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.actor._
import akka.cluster.Cluster
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, STMultiNodeSpec, MultiNodeSpec }
import akka.testkit.{ TestProbe, ImplicitSender, EventFilter }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

object ClusterClientStopSpec extends MultiNodeConfig {
  val client = role("client")
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.test.filter-leeway = 10s
    akka.contrib.cluster {

      client {
        heartbeat-interval = 1s
        acceptable-heartbeat-pause = 1s
        reconnect-timeout = 3s
      }

    }"""))

  class Service extends Actor {
    def receive = {
      case msg ⇒ sender() ! msg
    }
  }
}

class ClusterClientStopMultiJvmNode1 extends ClusterClientStopSpec
class ClusterClientStopMultiJvmNode2 extends ClusterClientStopSpec
class ClusterClientStopMultiJvmNode3 extends ClusterClientStopSpec

class ClusterClientStopSpec extends MultiNodeSpec(ClusterClientStopSpec) with STMultiNodeSpec with ImplicitSender {

  import ClusterClientStopSpec._

  override def initialParticipants: Int = 3

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address

    }
    enterBarrier(from.name + "-joined")
  }

  def awaitCount(expected: Int): Unit = {
    awaitAssert {
      DistributedPubSubExtension(system).mediator ! DistributedPubSubMediator.Count
      expectMsgType[Int] should be(expected)
    }
  }

  def initialContacts = Set(first, second).map { r ⇒
    system.actorSelection(node(r) / "user" / "receptionist")
  }

  "A Cluster Client" should {

    "startup cluster" in within(30.seconds) {
      join(first, first)
      join(second, first)
      runOn(first) {
        ClusterReceptionistExtension(system)
        val service = system.actorOf(Props(classOf[Service]), "testService")
        ClusterReceptionistExtension(system).registerService(service)
      }
      runOn(first, second) {
        awaitCount(1)
      }
      enterBarrier("cluster-started")
    }

    "stop if re-establish fails for too long time" in within(20.seconds) {
      runOn(client) {
        val c = system.actorOf(ClusterClient.props(initialContacts), "client1")
        c ! ClusterClient.Send("/user/testService", "hello", localAffinity = true)
        expectMsg("hello")

        enterBarrier("was-in-contact")

        watch(c)

        expectTerminated(c, 10.seconds)
        EventFilter.warning(start = "Receptionist reconnect not successful within", occurrences = 1)

      }

      runOn(first, second) {
        enterBarrier("was-in-contact")
        system.shutdown()
        system.awaitTermination(10.seconds)

      }

    }

  }

}