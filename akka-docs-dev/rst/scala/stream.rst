.. _stream-scala:

#######
Streams
#######

Motivation
==========

TODO

Quick Start: Reactive Tweets
============================

A typical use case for stream processing is consuming a live stream of data that we want to extract or aggregate some
other data from. In this example we'll consider consuming a stream of tweets and try to extract information concerning
Akka from them.

// todo needs rewording
We will also consider the problem inherent to all streaming solutions – "what if the subscriber is slower
to consume the live stream of data?" i.e. it is unable to keep up with processing the live data. Traditionally the solution
is often to buffer the elements, but this can (and usually *will*) cause eventual buffer overflows and instability of such systems.
Instead Akka Streams depend on explicit backpressure signals that allow to control what should happen in such scenarios.

Here's the model we'll be working with throughout the quickstart examples:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#model

Transforming and consuming simple streams
-----------------------------------------
Firstly, let's prepare our environment by creating an :class:`ActorSystem` and :class:`FlowMaterializer`,
which will be responsible for materializing and running the streams we're about to create:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#materializer-setup

The FlowMaterializer can optionally take ``MaterializerSettings`` which can be used to define

Let's assume we have a stream of tweets readily available, in Akka this is expressed as a :class:`Source[Out]`:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweet-source

Streams always start flowing from a :class:`Source[Out]` then can continue through :class:`Flow[In,Out]` elements or
more advanced graph elements to finally be consumed by a :class:`Sink[In]`. Both sources and flows provide stream operations
that can be used to transform the flowing data, a :class:`Sink` however does not since it's the "end of stream" and it's
behavior depends on the type of :class:`Sink` used.

In our case let's say we want to find all twitter handles of users which tweet about ``#akka``, the operations should look
familiar to anyone who has used the Scala Collections library, however they operate on streams and not collections of data:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-filter-map

Finally in order to :ref:`materialize <stream-materialization-scala>` and run the stream computation we need to attach
the Flow to a :class:`Sink[T]` that will *generate demand* and get the flow running. The simplest way to do this is to call
``runWith(Sink)`` on a ``Source[Out]``. For convenience a number of common Sinks are predefined and collected as metods on
the :class:``Sink`` `companion object <http://doc.akka.io/api/akka-stream-and-http-experimental/1.0-M1/#akka.stream.scaladsl.Sink$>`_.
For now let's simply print each author:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreachsink-println

or by using the shorthand version (which are defined only for the most popular sinks such as :class:`FoldSink` and :class:`ForeachSink`):

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreach-println

Materializing and running a stream always requires an :class:`FlowMaterializer` to be in scope.

Flattening sequences in streams
-------------------------------
In the previous section we were working on 1:1 relationships of elements which is the most common case, but sometimes
we might want to map from one element to a number of elements and receive a "flattened" stream, similarily like ``flatMap``
works on Scala Collections. In order to get a flattened stream of hashtags from our stream of tweets we can use the ``mapConcat``
combinator:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#hashtags-mapConcat

.. note::
  The name ``flatMap`` was counciously avoided as it gets special treatment in Scala's for comprehentions,
  which would not match semantically well to stream processing. // TODO should we write something like this?

Broadcasting a stream
---------------------
Now let's say we want to persist all hashtags, as well as all author names from this one live stream.
For example we'd like to write all auhtor handles into one file, and all hashtags into another file on disk.
This means we have to split the source stream into 2 streams which will handle the writing to these different files.

Elements that can be used to form such "fan-out" (or "fan-in") structures are referred to as "junctions" in Akka Streams.
One of these that we'll be using in this example is called :class:`Broadcast`, and it simply publishes elements from it's
input port to all of it's subscribers.

Junction operations are only available through constructing :class:`FlowGraph` s. Akka Streams intentionally separate the
linear operations "Flows" from "FlowGraphs" TODO BECAUSE [...]. FlowGraps are constructed like this:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#flow-graph-broadcast

As you can see, inside the :class:`FlowGraph` we use an implicit graph builder to mutably construct the graph
using the ``~>`` "edge operator" (also read as "connect" or "via" or "to"). Once we have the FlowGraph in the value ``g``
it is immutable and thread-safe to access as well as freely sharable. A graph can can be ``run()`` directly - assuming all
ports (sinks/sources) within a flow have been connected properly. It is possible to define :class:`PartialFlowGraph` s but
this will be covered in detail in :ref:`partial-flow-graph-scala`.

Back-pressure in action
-----------------------

One of the main advantages of Akka streams is that they *always* propagate back-pressure information from stream Sinks
(Subscribers) to their Sources (Publishers). It is not an optional feature, and is enabled at all times. To learn more
about the back-pressure protocol used by Akka Streams and all other Reactive Streams compatible implementations read
:ref:`back-pressure-explained-scala`.

A typical problem applications like this often face is that they are unable to process the incoming data fast enough,
and will start buffering incoming data until there's no more space to buffer, resulting in either ``OutOfMemoryError`` s
or other severe degradations of service responsiveness. With Akka streams buffering can and must be handled explicitly.
For example, if we're only interested in the "most recent tweets, with a buffer of 10 elements" this can be expressed using the ``buffer`` element:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-slow-consumption-dropHead

The ``buffer`` element takes an explicit and required ``OverflowStrategy``, which defines how the buffer should react
when it gets another element signalled while it's "full". Strategies provided include dropping the least recent element (``dropHead``),
dropping the entire buffer, signalling errors etc. Be sure to pick and choose the strategy that fits your use case best.

// TODO show the readline backpressure example? hm...

Materialized values
-------------------
So far we've been only processing data using Flows and consuming it into some kind of externak Sink - be it by printing
values or storing them in some external system. However sometimes the we may be interested in some value that can be
obtained from the materialized processing pipeline. For example, we want to know how many tweets we have processed.
While this question is not as obvous to give an answer to in case of an infinite stream of tweets (one way to answer
this question in a streaming setting would to create a stream of counts described as "*up until now*, we've processed N tweets"),
but in general it is possible to deal with finite streams and come up with a nice result such as a total count of elements.

First, let's write such elements counter using :class:`FoldSink` and then we'll see how it's possible to obtain materialized
values from an :class:`MaterializedMap` which is returned by materializing an Akka stream. We'll split execution into multiple
lines for the sake of explaining the concepts of ``Materializable`` elements and ``MaterializedType``

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count

First, we prepare the :class:`FoldSink` which will be used to sum all ``Int`` elements of the stream.
Next we connect the ``tweets`` stream though a ``map`` step which convers each tweet into the number ``1``,
finally we connect the flow ``to`` the previously prepared Sink. Notice that this step does *not* yet materialize the
processing pipeline, it merely prepares the description of the Flow, which is now connected to a Sink, and therefore can
be ``run()``, as indicated by it's type: :class:`RunnableFlow`. Next we call ``run()`` which uses the implicit :class:`FlowMaterializer`
to materialize and run the flow. The value returned by calling ``run()`` on a ``RunnableFlow`` or ``FlowGraph`` is ``MaterializedMap``,
which can be used to retrive materialized values from the running stream.

In order to extract an materialized value from a running stream it's possible to call ``get(Materializable)`` on a materializae map
obtained from materializing a flow or graph. Since ``FoldSink`` implements ``Materializable`` and implements the ``MaterializedType``
as ``Future[Int]`` we can use it to obtain the :class:`Future` which when completed will contain the total length of our tweets stream.
In case of the stream failing, this future would complete with a Failure.

Many elements in Akka streams provide materialized values which can be used for obtaining either results of computation or
steering these elements. This topic will be discussed in detail in the section **TODO**. Summing up this section, now we know
what happens behind the scenes when we run this one-liner, which is equivalent to the multi line version above:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count-oneline


Core concepts
=============

// TODO REWORD? This section explains the core types and concepts used in Akka Streams, from a more day-to-day use angle.
If we would like to get the big picture overview you may be interesed in reading :ref:`stream-design`.

Sources, Flows and Sinks
------------------------

// TODO: runnable flow, types - runWith

// TODO: talk about how creating and sharing a ``Flow.of[String]`` is useful etc.

.. _back-pressure-explained-scala:

Back-pressure explained
-----------------------

// TODO: explain the protocol and how it performs in slow-pub/fast-sub and fast-pub/slow-sub scenarios

Backpressure when Fast Publisher and Slow Subscriber
----------------------------------------------------

// TODO: Write me

Backpressure when Slow Publisher and Fast Subscriber
----------------------------------------------------

// TODO: Write me

In depth
========
// TODO: working with flows
// TODO: creating an empty flow
// TODO: materialization Flow -> RunnableFlow

// TODO: flattening, prefer static fanin/out, deadlocks

Streams of Streams
------------------

groupBy
^^^^^^^
// TODO: deserves it's own section? and explain the dangers? (dangling substream problem, subscription timeouts)

// TODO: Talk about ``flatten`` and ``FlattenStrategy``


.. _stream-materialization-scala:

Stream Materialization
----------------------

MaterializedMap
^^^^^^^^^^^^^^^


Optimisations
^^^^^^^^^^^^^
// TODO: not really to be covered right now, right?

Subscription timeouts
---------------------
// TODO: esp in groupby etc, if you dont subscribe to a stream son enougu it may be dead once you get to it


Working with Graphs
===================
// TODO: Don't forget adding the type parameter to the graph elements!

.. _partial-flow-graph-scala:

Constructing and combining Partial Flow Graphs
----------------------------------------------

// TODO: implement me

Constructing a Source or Sink from a Graph
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

// TODO: implement me

Dealing with cycles, deadlocks
------------------------------
// TODO: why to avoid cycles, how to enable if you really need to

// TODO: problem cases, expand-conflate, expand-filter

// TODO: working with rate

// TODO: custom processing

// TODO: stages and flexistuff

Streaming IO
============

// TODO: TCP here I guess

// TODO: Files if we get any, but not this week

Custom elements
===============

// TODO: So far we've been mostly using predefined elements, but sometimes that's not enough

Flexi Route
-----------

// TODO: "May sometimes be exactly what you need..."

Actor based custom elements
---------------------------

ActorPublisher
^^^^^^^^^^^^^^

ActorSubscriber
^^^^^^^^^^^^^^^


// TODO: Implementing Reactive Streams interfaces directly vs. extending ActorPublisher / ActoSubscriber???

Integrating with Actors
=======================

// TODO: Source.subscriber

// TODO: Sink.publisher

// TODO: Use the ImplicitFlowMaterializer if you have streams starting from inside actors.

// TODO: how do I create my own sources / sinks?

Integration with Reactive Streams enabled libraries
===================================================

// TODO: some info about reactive streams in general

// TODO: Simplly runWith(Sink.publisher) and runWith(Source.subscriber) to get the corresponding reactive streams types.

