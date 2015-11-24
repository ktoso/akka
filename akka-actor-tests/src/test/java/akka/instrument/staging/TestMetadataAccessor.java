/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument.staging;

import akka.actor.ActorRef;
import akka.instrument.MetadataAccessor;

public class TestMetadataAccessor extends MetadataAccessor<TestMetadata> {
    public String identifier = "id123";
    TestMetadata metadata = new TestMetadata(identifier);

    @Override
    public TestMetadata createMetadata(ActorRef actorRef, Class<?> clazz) {
        return metadata;
    }

    @Override
    public TestMetadata extractMetadata(Object info) {
        return metadata;
    }
}
