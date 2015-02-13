/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.StreamLayout

import scala.collection.immutable

object Graphs {

  sealed class InPort[-T](name: String) extends StreamLayout.InPort {
    override val toString = s"$name(${super.toString.split('@').tail.head})"
  }
  sealed class OutPort[+T](name: String) extends StreamLayout.OutPort {
    override val toString = s"$name(${super.toString.split('@').tail.head})"
  }

  /**
   * All incarnations of an [[IndexedInPort]] retain the same [[id]].
   * This may be used to identify multiple copies of a port pointing to teh same logical "position", e.g in selecting ports of a [[FlexiMerge]].
   */
  final class IndexedInPort[-T](val id: Int, name: String) extends InPort[T](name)

  /**
   * All incarnations of an [[IndexedOutPort]] retain the same [[id]].
   * This may be used to identify multiple copies of a port pointing to teh same logical "position", e.g in selecting ports of a [[FlexiRoute]].
   */
  final class IndexedOutPort[+T](val id: Int, name: String) extends OutPort[T](name)

  trait Ports {
    def inlets: immutable.Seq[InPort[_]]
    def outlets: immutable.Seq[OutPort[_]]

    def deepCopy(): Ports // could be this.type
  }

  final case class SourcePorts[+T](outlet: OutPort[T]) extends Ports {
    override val inlets: immutable.Seq[InPort[_]] = Nil
    override val outlets: immutable.Seq[OutPort[_]] = List(outlet)

    override def deepCopy(): SourcePorts[T] = SourcePorts(new OutPort(outlet.toString))
  }

  final case class FlowPorts[-I, +O](inlet: InPort[I], outlet: OutPort[O]) extends Ports {
    override val inlets: immutable.Seq[InPort[_]] = List(inlet)
    override val outlets: immutable.Seq[OutPort[_]] = List(outlet)

    override def deepCopy(): FlowPorts[I, O] = FlowPorts(new InPort(inlet.toString), new OutPort(outlet.toString))
  }

  final case class SinkPorts[-T](inlet: InPort[T]) extends Ports {
    override val inlets: immutable.Seq[InPort[_]] = List(inlet)
    override val outlets: immutable.Seq[OutPort[_]] = Nil

    override def deepCopy(): SinkPorts[T] = SinkPorts(new InPort(inlet.toString))
  }

  /**
   * In1  => Out1
   * Out2 <= In2
   */
  final case class BidiPorts[-In1, +Out1, -In2, +Out2](in1: InPort[In1], out1: OutPort[Out1], in2: InPort[In2], out2: OutPort[Out2]) extends Ports {
    override val inlets: immutable.Seq[InPort[_]] = List(in1, in2)
    override val outlets: immutable.Seq[OutPort[_]] = List(out1, out2)

    override def deepCopy(): BidiPorts[In1, Out1, In2, Out2] =
      BidiPorts(new InPort(in1.toString), new OutPort(out1.toString), new InPort(in2.toString), new OutPort(out2.toString))
  }

  trait Graph[+P <: Ports, +M] extends Materializable {
    override type MaterializedType <: M
    type Ports = P
    def ports: P
  }

  //  /**
  //   * This imports g1 and g2 by copying into the builder before running the
  //   * user code block. The user defines how to combine the materialized values
  //   * of the parts, and the return value is the new source’s outlet port (which
  //   * may have been imported with the graphs as well).
  //   *
  //   * To be extended to higher arities using the boilerplate plugin.
  //   */
  //  def source[G1 <: Graph[_, _], G2 <: Graph[_, _], Mat, T](g1: G1, g2: G2)(
  //    combineMat: (G1#MaterializedType, G2#MaterializedType) ⇒ Mat)(
  //      block: FlowGraphBuilder ⇒ (G1#Ports, G2#Ports) ⇒ Port[T]): Source[T, Mat] = ???
  //
  //  def example(g1: Source[Int, Future[Unit]], g2: Flow[Int, String, Unit]) =
  //    source(g1, g2)((f, _) ⇒ f) { implicit b ⇒
  //      (p1, p2) ⇒
  //        import FlowGraphImplicits._
  //
  //        p1.outlet ~> p2.inlet
  //        p2.outlet
  //    }
}