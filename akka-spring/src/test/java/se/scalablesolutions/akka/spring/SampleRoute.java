package se.scalablesolutions.akka.spring;

import org.apache.camel.builder.RouteBuilder;

public class SampleRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        from("direct:test").to("active-object:sample?method=foo");
    }
}
