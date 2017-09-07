package akka.typed.receptionist

import akka.cluster.Cluster
import akka.cluster.ddata.{ DistributedData, ORMultiMap, ORMultiMapKey, Replicator }
import akka.typed.ActorRef
import akka.typed.Behavior
import akka.typed.Terminated
import akka.util.TypedMultiMap
import akka.typed.scaladsl.Actor._

/**
 * A Receptionist is an entry point into an Actor hierarchy where select Actors
 * publish their identity together with the protocols that they implement. Other
 * Actors need only know the Receptionist’s identity in order to be able to use
 * the services of the registered Actors.
 */
object ClusterReceptionist {

  /**
   * Internal representation of [[ClusterReceptionist.ServiceKey]] which is needed
   * in order to use a TypedMultiMap (using keys with a type parameter does not
   * work in Scala 2.x).
   */
  trait AbstractServiceKey {
    type Type
  }

  /**
   * A service key is an object that implements this trait for a given protocol
   * T, meaning that it signifies that the type T is the entry point into the
   * protocol spoken by that service (think of it as the set of first messages
   * that a client could send).
   */
  trait ServiceKey[T] extends AbstractServiceKey {
    final override type Type = T

    def id: String
  }

  /*
  
  The router case:
  bound to a type
  can match (theKey, the ref), knowing that the ref is fine
  
  there is no unregister message, one has to terminate
  this matches the ORSet too nicely
  
  for now we act as if the local one does not exist
  since we're not use about it's usefulness.
  We think that locally extensions are more useful.
   */

  /**
   * The set of commands accepted by a Receptionist.
   */
  sealed trait Command
  private sealed trait InternalCommand extends Command

  /**
   * Associate the given [[akka.typed.ActorRef]] with the given [[ServiceKey]]. Multiple
   * registrations can be made for the same key. Unregistration is implied by
   * the end of the referenced Actor’s lifecycle.
   */
  final case class Register[T](key: ServiceKey[T], address: ActorRef[T])(val replyTo: ActorRef[Registered[T]]) extends Command

  final case class RegisterPrivate[T](key: ServiceKey[T], address: ActorRef[T])(val replyTo: ActorRef[Registered[T]]) extends Command

  case class Subscribe[T](key: ServiceKey[T])

  /**
   * Query the Receptionist for a list of all Actors implementing the given
   * protocol.
   */
  final case class Find[T](key: ServiceKey[T])(val replyTo: ActorRef[Listing[T]]) extends Command

  /**
   * Confirmation that the given [[akka.typed.ActorRef]] has been associated with the [[ServiceKey]].
   */
  final case class Registered[T](key: ServiceKey[T], address: ActorRef[T])

  /**
   * Current listing of all Actors that implement the protocol given by the [[ServiceKey]].
   */
  final case class Listing[T](key: ServiceKey[T], addresses: Set[ActorRef[T]])

  final val ReceptionistKey = ORMultiMapKey[AbstractServiceKey, ActorRef[_]]("ReceptionistKey")
  private final val EmptyORMultiMap = ORMultiMap.empty[AbstractServiceKey, ActorRef[_]]

  private final case class UpdateInternalState(map: ORMultiMap[AbstractServiceKey, ActorRef[_]]) extends InternalCommand

  /**
   * Initial behavior of a receptionist, used to create a new receptionist like in the following:
   *
   * {{{
   * val receptionist: ActorRef[Receptionist.Command] = ctx.spawn(Props(Receptionist.behavior), "receptionist")
   * }}}
   */
  val behavior: Behavior[Command] =
    deferred { ctx ⇒
      import akka.typed.scaladsl.adapter._
      val untypedSystem = ctx.system.toUntyped

      val replicator = DistributedData(untypedSystem).replicator
      implicit val cluster = Cluster(untypedSystem)

      ???
      val adapter: ActorRef[String] = ???
      //      val adapter = ctx.spawnAdapter[Any] {
      //        case changed @ Replicator.Changed(ReceptionistKey) ⇒
      //          val value = changed.get(ReceptionistKey)
      //          UpdateInternalState(value)
      //      }

      replicator ! Replicator.Subscribe(ReceptionistKey, adapter.toUntyped)

      behavior(replicator, ORMultiMap.empty)(cluster)
    }

  // private type KV[K <: AbstractServiceKey] = ActorRef[K#Type]

  private def behavior(replicator: akka.actor.ActorRef, map: ORMultiMap[AbstractServiceKey, ActorRef[_]])(implicit cluster: Cluster): Behavior[Command] =
    immutable[Command] { (ctx, msg) ⇒
      msg match {
        case r: Register[t] ⇒
          ctx.watch(r.address)

          r.replyTo ! Registered(r.key, r.address)

          val set: Set[ActorRef[_]] = map.get(r.key).getOrElse(Set.empty)
          val value = map.+(r.key → (set + r.address))
          // TODO does it make sense to configure the consistency?
          replicator ! Replicator.Update(ReceptionistKey, EmptyORMultiMap, Replicator.WriteLocal) { map ⇒
            map.addBinding(r.key, r.address)
          }

          behavior(replicator, value)

        case f: Find[t] ⇒
          ???
          //          val found = map.get(f.key).getOrElse(Set.empty)
          //          f.replyTo ! Listing(f.key, found)
          same

        case UpdateInternalState(m) ⇒
          behavior(replicator, m)
      }
    } onSignal {
      case (ctx, Terminated(ref)) ⇒
        ??? // behavior(map.(:-))

    }
}

abstract class ClusterReceptionist
