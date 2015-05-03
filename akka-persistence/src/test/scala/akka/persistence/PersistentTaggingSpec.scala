/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import akka.actor._
import akka.persistence.journal.Tagger
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.collection.immutable

object PersistentTaggingSpec {

  trait HasCountry { def countryCode: String }
  trait Tagged { def tags: immutable.Set[String] }

  final case class AnonymousDataChanged(value: Int)
  final case class TaggedDataChanged(tags: immutable.Set[String], value: Int) extends Tagged
  final case class UserDataChanged(countryCode: String, age: Int) extends HasCountry
  // same as UserDataChanged, but different tagging bindings configured
  final case class UserDataChangedTagAll(countryCode: String, age: Int) extends HasCountry

  class UserDataTagger extends Tagger[Any] {
    val Adult = Set("adult")
    val Minor = Set("minor")

    // supports dis-joint classes with age field
    override def tagsFor(message: Any) = {
      val age = message match {
        case UserDataChanged(_, a)       ⇒ a
        case UserDataChangedTagAll(_, a) ⇒ a
        case _                           ⇒ throw new Exception(s"Unsupported message [$message] by tagger ${this.getClass}")
      }

      if (age >= 18) Adult else Minor
    }
  }

  class CountryTagger extends Tagger[HasCountry] {
    override def tagsFor(message: HasCountry) = Set(message.countryCode)
  }

  class TaggedTagger extends Tagger[Tagged] {
    override def tagsFor(message: Tagged) = message.tags
  }

  class PersistAllIncomingActor(name: String) extends NamedPersistentActor(name) with PersistentActor {
    val persistIncoming: Receive = {
      case in ⇒
        persist(in) { e ⇒
          sender() ! e
        }
    }

    override def receiveRecover = persistIncoming
    override def receiveCommand = persistIncoming
  }

}

abstract class PersistentTaggingSpec(journalConfig: Config, taggingConfig: Config) extends AkkaSpec(journalConfig.withFallback(taggingConfig))
  with PersistenceSpec with ImplicitSender {
  import PersistentTaggingSpec._

  def this(journalConfig: Config) {
    this(journalConfig, ConfigFactory.parseString(
      s"""
         |akka.persistence.tagging {
         | taggers {
         |   country-code = "${classOf[PersistentTaggingSpec].getCanonicalName}$$CountryTagger"
         |   app-tagged   = "${classOf[PersistentTaggingSpec].getCanonicalName}$$TaggedTagger"
         |   user-data    = "${classOf[PersistentTaggingSpec].getCanonicalName}$$UserDataTagger"
         | }
         |
         | tagger-bindings {
         |   "${classOf[PersistentTaggingSpec].getCanonicalName}$$Tagged"                = [ app-tagged ]
         |   "${classOf[PersistentTaggingSpec].getCanonicalName}$$HasCountry"            = [ country-code ]
         |   "${classOf[PersistentTaggingSpec].getCanonicalName}$$UserDataChanged"       = [ user-data ] // tests "most-specific-wins"
         |   "${classOf[PersistentTaggingSpec].getCanonicalName}$$UserDataChangedTagAll" = [ user-data, country-code ]
         | }
         |}
      """.stripMargin))
  }

  def persister(name: String) =
    system.actorOf(Props(classOf[PersistAllIncomingActor], name), name)

  def tagsFor(in: Any) = Persistence(system).taggerFor(in).tagsFor(in)

  "Tagging" must {

    "not tag tag if no tagger binding matches" in {
      tagsFor("no-tags") should ===(Set.empty[String])
    }

    "tag when super-class has tagger binding" in {
      // Tagged trait has tagger bound
      tagsFor(TaggedDataChanged(Set("a", "b"), 42)) should ===(Set("a", "b"))
    }

    "tag with most-specific matching tagger binding" in {
      // tagger specified for this specific class, HasCountry binding will be ignored
      tagsFor(UserDataChanged(countryCode = "pl", 42)) should ===(Set("adult"))
    }

    "tag with multiple defined taggers when multiple are bound" in {
      // user-data tagger as well as country-code tagger configured
      tagsFor(UserDataChangedTagAll(countryCode = "pl", 42)) should ===(Set("adult") ++ Set("pl"))
    }
  }

}

//class LeveldbPersistentTaggingSpec extends PersistentTaggingSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentTaggingSpec"))

class InmemPersistentTaggingSpec extends PersistentTaggingSpec(PersistenceSpec.config("inmem", "InmemPersistentTaggingSpec"))
