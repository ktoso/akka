package akka.persistence.query.journal.leveldb

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.persistence.query.{MockReadJournal, PersistenceQuery}
import akka.persistence.{Persistence, PersistentActor}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

object LeveldbReadJournalSpec {
  final class LeveldbPersistentActor(name: String, probe: ActorRef) extends PersistentActor {
    override def persistenceId = ""
    override def receiveRecover: Receive = {
      case m => probe ! m
    }

    override def receiveCommand: Receive = {
      case m => persist(m) { probe ! _ }
    }
  }
}

class PersistenceQueryLeveldbSpec(_config: Config) extends WordSpecLike with Matchers with BeforeAndAfterAll {

  val config = ConfigFactory.parseString(
    s"""|akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
        |akka.persistence.journal.leveldb.dir = "target/journal-${getClass.getSimpleName}"
     """.stripMargin)
    .withFallback(_config)
    .withFallback(ConfigFactory.load())

  val system = ActorSystem(getClass.getSimpleName + "System", config)

  val persistence = Persistence(system)
  val persistenceQuery = PersistenceQuery(system)

  var store: ActorRef = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    store = system.actorOf(Props[SharedLeveldbStore](), "leveldb-store")
    SharedLeveldbJournal.setStore(store, system)

    val readJournal = persistenceQuery.readJournalFor(LeveldbReadJournal.Identifier).asInstanceOf[LeveldbReadJournal]
    readJournal.useSharedLeveldbStore(store)
  }

  override def afterAll(): Unit = {
    Await.ready(system.terminate(), 3.seconds)
    super.afterAll()
  }

}

class LeveldbReadJournalSpec(config: Config) extends PersistenceQueryLeveldbSpec(config) {

  def this() {
    this(ConfigFactory.parseString(s"""
      """.stripMargin))
    }


   "LeveldbReadJournal" must {
     "work with shared leveldb WriteJournal" in {
         PersistenceQuery.get(system).readJournalFor(MockReadJournal.Identifier)
     }
   }

 }
