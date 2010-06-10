package sample.camel;

import org.apache.camel.Body;
import org.apache.camel.Header;
import se.scalablesolutions.akka.actor.annotation.consume;

/**
 * @author Martin Krasser
 */
public class RemoteConsumerPojo2 {

    @consume("jetty:http://localhost:6644/camel/remote-active-object-2")
    public String foo(@Body String body, @Header("name") String header) {
        return String.format("remote2: body=%s header=%s", body, header);
    }

}