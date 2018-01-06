# Akka Streams over Network boundaries 

Stream references, or "stream refs" for short, allow distributing 

Please note that while stream refs *seemingly* share many similarities with 
"distributed stream processing" frameworks, such as Spark or Beam, they have are not on the same level of abstraction as the other ones. Stream refs are trivial to use, high performance,
and flow-controller. The most intuitive way of thinking about them would be "like ActorRef, but for Akka Streams (and thus, by induction, for Reactive Streams)"

## Source Refs - offering streaming data over network

![simple-graph-example.png](../images/source-ref-dance.png)

## Sink Refs - offering to receive streaming data

![simple-graph-example.png](../images/sink-ref-dance.png)

