package sample.cluster.stats

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberUp
import akka.contrib.pattern.ClusterSingletonManager
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import sample.cluster.stats.StatsMessages._

object StatsSampleSingleMasterSpecConfig extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val first = role("first")
  val second = role("second")
  val third = role("thrid")

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.roles = [compute]
    # don't use sigar for tests, native lib not in path
    akka.cluster.metrics.collector-class = akka.cluster.JmxMetricsCollector
    #//#router-deploy-config  
    akka.actor.deployment {
      /singleton/statsService/workerRouter {
          router = consistent-hashing-pool
          nr-of-instances = 100
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 3
            allow-local-routees = on
            use-role = compute
          }
        }
    }
    #//#router-deploy-config  
    """))

}

// need one concrete test class per node
class StatsSampleSingleMasterSpecMultiJvmNode1 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterSpecMultiJvmNode2 extends StatsSampleSingleMasterSpec
class StatsSampleSingleMasterSpecMultiJvmNode3 extends StatsSampleSingleMasterSpec

abstract class StatsSampleSingleMasterSpec extends MultiNodeSpec(StatsSampleSingleMasterSpecConfig)
  with WordSpecLike with Matchers with BeforeAndAfterAll with ImplicitSender {

  import StatsSampleSingleMasterSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  "The japi stats sample with single master" must {
    "illustrate how to startup cluster" in within(15 seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address

      Cluster(system) join firstAddress

      receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
        Set(firstAddress, secondAddress, thirdAddress))

      Cluster(system).unsubscribe(testActor)

      system.actorOf(ClusterSingletonManager.defaultProps(
        Props[StatsService],
        singletonName = "statsService",
        terminationMessage = PoisonPill,
        role = null), name = "singleton")

      system.actorOf(Props[StatsFacade], "statsFacade")

      testConductor.enter("all-up")
    }

    "show usage of the statsFacade" in within(40 seconds) {
      val facade = system.actorSelection(RootActorPath(node(third).address) / "user" / "statsFacade")

      // eventually the service should be ok,
      // service and worker nodes might not be up yet
      awaitAssert {
        facade ! new StatsJob("this is the text that will be analyzed")
        expectMsgType[StatsResult](1.second).getMeanWordLength should be(3.875 +- 0.001)
      }

      testConductor.enter("done")
    }
  }

}