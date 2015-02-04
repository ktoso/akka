/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.scaladsl
import akka.stream.Supervision

/**
 * Holds attributes which can be used to alter [[Flow]] or [[FlowGraph]]
 * materialization.
 */
abstract class OperationAttributes private () {
  private[akka] def asScala: scaladsl.OperationAttributes

  /**
   * Adds given attributes to the end of these attributes.
   */
  def and(other: OperationAttributes) = new OperationAttributes {
    private[akka] def asScala = this.asScala and other.asScala
  }
}

/**
 * Various attributes that can be applied to [[Flow]] or [[FlowGraph]]
 * materialization.
 */
object OperationAttributes {

  /**
   * Specifies the name of the operation.
   */
  def name(name: String): OperationAttributes =
    if (name eq null) none
    else
      new OperationAttributes {
        private[akka] def asScala = scaladsl.OperationAttributes.name(name)
      }

  /**
   * Specifies the initial and maximum size of the input buffer.
   */
  def inputBuffer(initial: Int, max: Int): OperationAttributes = new OperationAttributes {
    private[akka] def asScala = scaladsl.OperationAttributes.inputBuffer(initial, max)
  }

  /**
   * Specifies the name of the dispatcher.
   */
  def dispatcher(dispatcher: String): OperationAttributes = new OperationAttributes {
    private[akka] def asScala = scaladsl.OperationAttributes.dispatcher(dispatcher)
  }

  /**
   * Decides how exceptions from application code are to be handled.
   */
  def supervisionStrategy(decider: japi.Function[Throwable, Supervision.Directive]): OperationAttributes =
    new OperationAttributes {
      private[akka] def asScala = scaladsl.OperationAttributes.supervisionStrategy(e ⇒ decider.apply(e))
    }

  private[akka] val none: OperationAttributes = new OperationAttributes {
    private[akka] def asScala = scaladsl.OperationAttributes.none
  }
}
