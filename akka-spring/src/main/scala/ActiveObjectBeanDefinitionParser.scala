/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser
import org.springframework.beans.factory.xml.ParserContext
import org.w3c.dom.Element


/**
 * Parser for custom namespace configuration.
 * @author michaelkober
 */
class ActiveObjectBeanDefinitionParser extends AbstractSingleBeanDefinitionParser with ActiveObjectParser {
  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#doParse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, org.springframework.beans.factory.support.BeanDefinitionBuilder)
   */
  override def doParse(element: Element, parserContext: ParserContext, builder: BeanDefinitionBuilder) {
    val activeObjectConf = parseActiveObject(element)
    activeObjectConf.setAsProperties(builder)
  }

  /*
   * @see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser#getBeanClass(org.w3c.dom.Element)
   */
  override def getBeanClass(element: Element): Class[_] = classOf[ActiveObjectFactoryBean]
}
