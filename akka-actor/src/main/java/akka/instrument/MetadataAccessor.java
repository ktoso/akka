/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */


package akka.instrument;

import akka.actor.ActorRef;
import akka.actor.ActorRefWithCell;

/**
 * Convenience class to attach and extract metadata objects for ActorRefs.
 *
 * Java is used to circumvent package private in Scala. Java to the rescue...
 */
public abstract class MetadataAccessor<T> {

    /**
     * MetadataAccessor factory for supporting wrapped accessors.
     */
    public static class Factory {
        public static Factory DEFAULT = new Factory();

        /*
         * Default implementation doesn't do anything, just returns the same accessor.
         */
        public <T> MetadataAccessor<T> create(MetadataAccessor<T> accessor) {
            return accessor;
        }
    }

    /**
     * Attach a metadata object to an ActorRef, if it has an underlying cell.
     * The createMetadata method is called to create the metadata object.
     * @return attached metadata, otherwise null
     */
    public T attachTo(ActorRef actorRef) {
        if (actorRef instanceof ActorRefWithCell) {
            ActorRefWithCell actorRefWithCell = (ActorRefWithCell) actorRef;
            Class clazz = actorRefWithCell.underlying().props().actorClass();
            T metadata = createMetadata(actorRef, clazz);
            actorRefWithCell.metadata_$eq(metadata);
            return metadata;
        }

        return null;
    }

    /**
     * Remove a metadata object from an ActorRef by clearing it out.
     * @return attached metadata, otherwise null
     */
    public T removeFrom(ActorRef actorRef) {
        T metaData = extractFrom(actorRef);
        if (metaData != null) {
            ActorRefWithCell actorRefWithCell = (ActorRefWithCell) actorRef;
            actorRefWithCell.metadata_$eq(null);
        }

        return metaData;
    }

    /**
     * Create metadata based on an ActorRef and its Actor class.
     * @return metadata to attach
     */
    protected abstract T createMetadata(ActorRef actorRef, Class<?> clazz);

    /**
     * Extract the metadata object from an ActorRef, if it has an underlying cell.
     * @return extracted metadata, otherwise null
     */
    public T extractFrom(ActorRef actorRef) {
        if (actorRef instanceof ActorRefWithCell) {
            return extractMetadata(((ActorRefWithCell) actorRef).metadata());
        }

        return null;
    }

    /**
     * Extract or cast a metadata object to the correct type.
     * @return extracted metadata, otherwise null
     */
    protected abstract T extractMetadata(Object info);

}
