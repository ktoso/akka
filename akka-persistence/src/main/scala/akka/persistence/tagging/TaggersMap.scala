package akka.persistence.tagging

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ExtendedActorSystem
import akka.event.LoggingAdapter
import akka.persistence.journal.CombinedTagger
import akka.persistence.journal.NoopTagger
import akka.persistence.journal.Tagger

import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.Try

/** INTERNAL API */
//noinspection ZeroIndexToHead
private[akka] final class TaggersMap(
  map: ConcurrentHashMap[Class[_], Tagger[Any]],
  bindings: immutable.Seq[(Class[_], Tagger[Any])],
  log: LoggingAdapter) {

  def get(clazz: Class[_]): Tagger[Any] = {
    map.get(clazz) match {
      case null ⇒ // bindings are ordered from most specific to least specific
        val value = bindings filter {
          _._1 isAssignableFrom clazz
        } match {
          case Vector()            ⇒ NoopTagger
          case Vector((_, tagger)) ⇒ tagger
          case possibilities       ⇒ CombinedTagger(possibilities.map(_._2))
        }
        map.putIfAbsent(clazz, value) match {
          case null ⇒
            log.debug(s"Using tagger[{}] for message [{}]", value.getClass.getName, clazz.getName)
            value
          case some ⇒ some
        }
      case value ⇒ value
    }
  }

}

/** INTERNAL API */
private[akka] object TaggersMap {
  type Name = String
  type FQN = String
  type ClassHandler = (Class[_], Tagger[Any])

  def apply(
    system: ExtendedActorSystem,
    taggers: Map[Name, FQN],
    taggerBindings: Map[FQN, immutable.Seq[Name]]) = {

    // A Map of handler from alias to implementation (i.e. class implementing akka.serialization.Serializer)
    // For example this defines a handler named 'java': `"java" -> akka.serialization.JavaSerializer`
    val handlers = for ((k: String, v: String) ← taggers) yield k -> instantiate[Tagger[Any]](v, system).get

    // bindings is a Seq of tuple representing the mapping from Class to handler.
    // It is primarily ordered by the most specific classes first, and secondly in the configured order.
    val bindings: immutable.Seq[ClassHandler] = {
      val bs = for ((k: String, ts: immutable.Seq[String]) ← taggerBindings)
        yield (system.dynamicAccess.getClassFor[Any](k).get, CombinedTagger(ts.map(handlers(_))))

      sort(bs)
    }

    val backing = (new ConcurrentHashMap[Class[_], Tagger[Any]] /: bindings) { case (map, (c, s)) ⇒ map.put(c, s); map }

    new TaggersMap(backing, bindings, system.log)
  }

  /**
   * Tries to load the specified Serializer by the fully-qualified name; the actual
   * loading is performed by the system’s [[akka.actor.DynamicAccess]].
   */
  private def instantiate[T: ClassTag](fqn: FQN, system: ExtendedActorSystem): Try[T] =
    system.dynamicAccess.createInstanceFor[T](fqn, List(classOf[ExtendedActorSystem] -> system)) recoverWith {
      case _: NoSuchMethodException ⇒ system.dynamicAccess.createInstanceFor[T](fqn, Nil)
    }

  /**
   * Sort so that subtypes always precede their supertypes, but without
   * obeying any order between unrelated subtypes (insert sort).
   */
  private def sort[T](in: Iterable[(Class[_], T)]): immutable.Seq[(Class[_], T)] =
    (new ArrayBuffer[(Class[_], T)](in.size) /: in) { (buf, ca) ⇒
      buf.indexWhere(_._1 isAssignableFrom ca._1) match {
        case -1 ⇒ buf append ca
        case x  ⇒ buf insert (x, ca)
      }
      buf
    }.to[immutable.Seq]

}