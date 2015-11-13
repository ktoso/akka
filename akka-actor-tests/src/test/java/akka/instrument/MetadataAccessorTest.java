/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument;

import akka.actor.*;
import akka.instrument.staging.TestMetadata;
import akka.instrument.staging.TestMetadataAccessor;
import org.junit.Test;
import scala.concurrent.duration.Duration;

import static org.junit.Assert.*;

public class MetadataAccessorTest {
    public static class ActorA extends UntypedActor {
        @Override
        public void onReceive(Object message) throws Exception {
        }
    }

    TestMetadataAccessor metadataAccessor = new TestMetadataAccessor();

    @Test
    public void testAttachToAndExtractFrom() {
        ActorSystem as = ActorSystem.create("MyTestAS");
        try {
            ActorRef actorRef = as.actorOf(Props.create(ActorA.class));
            ActorRefWithCell actorRefCell = (ActorRefWithCell) actorRef;

            // attachTo
            assertNull(actorRefCell.metadata());
            metadataAccessor.attachTo(actorRef);
            assertNotNull(actorRefCell.metadata());

            // extractFrom
            Object extract = metadataAccessor.extractFrom(actorRef);
            assertNotNull(extract);
            assertEquals(metadataAccessor.identifier, ((TestMetadata) extract).identifier);
        }
        finally {
            as.shutdown();
            as.awaitTermination(Duration.create("5 seconds"));
        }
    }
}
