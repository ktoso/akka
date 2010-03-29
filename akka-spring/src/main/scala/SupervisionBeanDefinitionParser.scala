/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import se.scalablesolutions.akka.util.Logging
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.{ParserContext, AbstractSingleBeanDefinitionParser}
import se.scalablesolutions.akka.config.JavaConfig._
import AkkaSpringConfigurationTags._


import org.w3c.dom.Element
import org.springframework.util.xml.DomUtils


/**
 * Parser for custom namespace for Akka declarative supervisor configuration.
 * @author michaelkober
 */
class SupervisionBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActiveObjectBeanDefinitionParser {
  /* (non-Javadoc)
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    parseSupervisor(element, builder)
  }

  /**
   * made accessible for testing
   */
  private[akka] def parseSupervisor(element: Element, builder: BeanDefinitionBuilder) {
    val strategyElement = mandatoryElement(element, STRATEGY_TAG);
    val activeObjectsElement = mandatoryElement(element, ACTIVE_OBJECTS_TAG);
    parseRestartStrategy(strategyElement, builder)
    parseActiveObjectList(activeObjectsElement, builder)
  }

  private[akka] def parseRestartStrategy(element: Element, builder: BeanDefinitionBuilder) {
    val failover = if (mandatory(element, FAILOVER) == "AllForOne") new AllForOne() else new OneForOne()
    val timeRange = mandatory(element, TIME_RANGE).toInt
    val retries = mandatory(element, RETRIES).toInt
    val trapExitsElement = mandatoryElement(element, TRAP_EXISTS_TAG)
    val trapExceptions = parseTrapExits(trapExitsElement)
    val restartStrategy = new RestartStrategy(failover, retries, timeRange, trapExceptions)
    builder.addPropertyValue("restartStrategy", restartStrategy)
  }

  private[akka] def parseActiveObjectList(element: Element, builder: BeanDefinitionBuilder) {
    val activeObjects = DomUtils.getChildElementsByTagName(element, ACTIVE_OBJECT_TAG).toArray.toList.asInstanceOf[List[Element]]
    val activeObjectProperties = activeObjects.map(parseActiveObject(_))
    builder.addPropertyValue("supervised", activeObjectProperties)
  }

  private def parseTrapExits(element: Element): Array[Class[_ <: Throwable]] = {
    import StringReflect._
    val trapExits = DomUtils.getChildElementsByTagName(element, TRAP_EXIT_TAG).toArray.toList.asInstanceOf[List[Element]]
    trapExits.map(DomUtils.getTextValue(_).toClass).toArray
  }

  /* 
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element) = classOf[SupervisionFactoryBean]
}