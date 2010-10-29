package sample.camel

import org.apache.camel.impl.{DefaultCamelContext, SimpleRegistry}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.spring.spi.ApplicationContextRegistry
import org.springframework.context.support.ClassPathXmlApplicationContext

import akka.actor.{Actor, ActorRegistry, TypedActor}
import akka.camel._

/**
 * @author Martin Krasser
 */
object StandaloneApplication extends Application {
  import CamelContextManager._
  import CamelServiceManager._

  // 'externally' register typed actors
  val registry = new SimpleRegistry
  registry.put("sample", TypedActor.newInstance(classOf[BeanIntf], classOf[BeanImpl]))

  // customize CamelContext
  CamelContextManager.init(new DefaultCamelContext(registry))
  CamelContextManager.mandatoryContext.addRoutes(new StandaloneApplicationRoute)

  startCamelService

  // access 'externally' registered typed actors
  assert("hello msg1" == mandatoryContext.createProducerTemplate.requestBody("direct:test", "msg1"))

  mandatoryService.awaitEndpointActivation(1) {
    // 'internally' register typed actor (requires CamelService)
    TypedActor.newInstance(classOf[TypedConsumer2], classOf[TypedConsumer2Impl])
  }

  // access 'internally' (automatically) registered typed-actors
  // (see @consume annotation value at TypedConsumer2.foo method)
  assert("default: msg3" == mandatoryContext.createProducerTemplate.requestBody("direct:default", "msg3"))

  stopCamelService

  ActorRegistry.shutdownAll
}

class StandaloneApplicationRoute extends RouteBuilder {
  def configure = {
    // route to typed actors (in SimpleRegistry)
    from("direct:test").to("typed-actor:sample?method=foo")
  }
}

object StandaloneSpringApplication extends Application {
  import CamelContextManager._

  // load Spring application context
  val appctx = new ClassPathXmlApplicationContext("/context-standalone.xml")

  // access 'externally' registered typed actors with typed-actor component
  assert("hello msg3" == mandatoryTemplate.requestBody("direct:test3", "msg3"))

  appctx.close

  ActorRegistry.shutdownAll
}

class StandaloneSpringApplicationRoute extends RouteBuilder {
  def configure = {
    // routes to typed actor (in ApplicationContextRegistry)
    from("direct:test3").to("typed-actor:ta?method=foo")
  }
}

object StandaloneJmsApplication extends Application {
  import CamelServiceManager._

  val context = new ClassPathXmlApplicationContext("/context-jms.xml")
  val registry = new ApplicationContextRegistry(context)

  // Init CamelContextManager with custom CamelContext
  CamelContextManager.init(new DefaultCamelContext(registry))

  startCamelService

  val jmsUri = "jms:topic:test"
  val jmsPublisher =   Actor.actorOf(new Publisher("jms-publisher", jmsUri)).start

  mandatoryService.awaitEndpointActivation(2) {
    Actor.actorOf(new Subscriber("jms-subscriber-1", jmsUri)).start
    Actor.actorOf(new Subscriber("jms-subscriber-2", jmsUri)).start
  }

  // Send 10 messages to via publisher actor
  for(i <- 1 to 10) {
    jmsPublisher ! ("Akka rocks (%d)" format i)
  }

  // Send 10 messages to JMS topic directly
  for(i <- 1 to 10) {
    CamelContextManager.mandatoryTemplate.sendBody(jmsUri, "Camel rocks (%d)" format i)
  }

  // Wait a bit for subscribes to receive messages
  Thread.sleep(1000)

  stopCamelService
  ActorRegistry.shutdownAll
}
