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

**// todo needs rewording**
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

The :class:`FlowMaterializer` can optionally take :class:`MaterializerSettings` which can be used to define
materialization properties, such as default buffer sizes, the dispatched to be used by the pipeline etc.
These can be overridden on an element-by-element basis or for an entire section, but this will be discussed in depth in :ref:`stream-section-configuration`.

Let's assume we have a stream of tweets readily available, in Akka this is expressed as a :class:`Source[Out]`:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweet-source

Streams always start flowing from a :class:`Source[Out]` then can continue through :class:`Flow[In,Out]` elements or
more advanced graph elements to finally be consumed by a :class:`Sink[In]`. Both Sources and Flows provide stream operations
that can be used to transform the flowing data, a :class:`Sink` however does not since it's the "end of stream" and its
behavior depends on the type of :class:`Sink` used.

In our case let's say we want to find all twitter handles of users which tweet about ``#akka``, the operations should look
familiar to anyone who has used the Scala Collections library, however they operate on streams and not collections of data:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-filter-map

Finally in order to :ref:`materialize <stream-materialization-scala>` and run the stream computation we need to attach
the Flow to a :class:`Sink[T]` that will *generate demand* and get the flow running. The simplest way to do this is to call
``runWith(Sink)`` on a ``Source[Out]``. For convenience a number of common Sinks are predefined and collected as methods on
the :class:``Sink`` `companion object <http://doc.akka.io/api/akka-stream-and-http-experimental/1.0-M2-SNAPSHOT/#akka.stream.scaladsl.Sink$>`_.
For now let's simply print each author:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreachsink-println

or by using the shorthand version (which are defined only for the most popular sinks such as :class:`FoldSink` and :class:`ForeachSink`):

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#authors-foreach-println

Materializing and running a stream always requires a :class:`FlowMaterializer` to be in scope.

Flattening sequences in streams
-------------------------------
In the previous section we were working on 1:1 relationships of elements which is the most common case, but sometimes
we might want to map from one element to a number of elements and receive a "flattened" stream, similarly like ``flatMap``
works on Scala Collections. In order to get a flattened stream of hashtags from our stream of tweets we can use the ``mapConcat``
combinator:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#hashtags-mapConcat

.. note::
  The name ``flatMap`` was consciously avoided as it gets special treatment in Scala's for comprehensions,
  which would not match semantically well to stream processing. // TODO should we write something like this?

Broadcasting a stream
---------------------
Now let's say we want to persist all hashtags, as well as all author names from this one live stream.
For example we'd like to write all author handles into one file, and all hashtags into another file on disk.
This means we have to split the source stream into 2 streams which will handle the writing to these different files.

Elements that can be used to form such "fan-out" (or "fan-in") structures are referred to as "junctions" in Akka Streams.
One of these that we'll be using in this example is called :class:`Broadcast`, and it simply emits elements from its
input port to all of its output ports.

Junction operations are only available through constructing :class:`FlowGraph` s. Akka Streams intentionally separate the
linear operations "Flows" from "FlowGraphs" TODO BECAUSE [...]. FlowGraphs are constructed like this:

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#flow-graph-broadcast

As you can see, inside the :class:`FlowGraph` we use an implicit graph builder to mutably construct the graph
using the ``~>`` "edge operator" (also read as "connect" or "via" or "to"). Once we have the FlowGraph in the value ``g``
it is immutable and thread-safe to access as well as freely sharable. A graph can can be ``run()`` directly - assuming all
ports (sinks/sources) within a flow have been connected properly. It is possible :class:`PartialFlowGraph` s where this
is not required but this will be covered in detail in :ref:`partial-flow-graph-scala`.

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
when it gets another element signalled while it is "full". Strategies provided include dropping the least recent element (``dropHead``),
dropping the entire buffer, signalling errors etc. Be sure to pick and choose the strategy that fits your use case best.

Materialized values
-------------------
So far we've been only processing data using Flows and consuming it into some kind of external Sink - be it by printing
values or storing them in some external system. However sometimes the we may be interested in some value that can be
obtained from the materialized processing pipeline. For example, we want to know how many tweets we have processed.
While this question is not as obvious to give an answer to in case of an infinite stream of tweets (one way to answer
this question in a streaming setting would to create a stream of counts described as "*up until now*, we've processed N tweets"),
but in general it is possible to deal with finite streams and come up with a nice result such as a total count of elements.

First, let's write such elements counter using :class:`FoldSink` and then we'll see how it's possible to obtain materialized
values from an :class:`MaterializedMap` which is returned by materializing an Akka stream. We'll split execution into multiple
lines for the sake of explaining the concepts of ``Materializable`` elements and ``MaterializedType``

.. includecode:: code/docs/stream/TwitterStreamQuickstartDocSpec.scala#tweets-fold-count

First, we prepare the :class:`FoldSink` which will be used to sum all ``Int`` elements of the stream.
Next we connect the ``tweets`` stream though a ``map`` step which converts each tweet into the number ``1``,
finally we connect the flow ``to`` the previously prepared Sink. Notice that this step does *not* yet materialize the
processing pipeline, it merely prepares the description of the Flow, which is now connected to a Sink, and therefore can
be ``run()``, as indicated by it's type: :class:`RunnableFlow`. Next we call ``run()`` which uses the implicit :class:`FlowMaterializer`
to materialize and run the flow. The value returned by calling ``run()`` on a ``RunnableFlow`` or ``FlowGraph`` is ``MaterializedMap``,
which can be used to retrieve materialized values from the running stream.

In order to extract an materialized value from a running stream it's possible to call ``get(Materializable)`` on a materialize map
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
If we would like to get the big picture overview you may be interested in reading :ref:`stream-design`.

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
// TODO: deserves it's own section? and explain the dangers? (dangling sub-stream problem, subscription timeouts)

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
// TODO: esp in groupBy etc, if you dont subscribe to a stream son enough it may be dead once you get to it


.. _stream-section-configuration:

Section configuration
---------------------
// TODO: It's possible to configure sections of a graph


Working with Graphs
===================
Akka streams are unique in the way they handle and expose computation graphs - instead of hiding the fact that the
processing pipeline is in fact a graph in a purely "fluent" DSL graph operations are written in a DSL that graphically
reassembles and embraced the fact that the built pipeline is in fact a Graph. Linear operations have been already explained
in the :ref:`sources-flows-sinks-scala` section **TODO rethink to which section to link here**


**// TODO: Don't forget adding the type parameter to the graph elements!**

.. _flow-graph-scala:

Constructing Flow Graphs
------------------------

**// TODO describe normal graphs**



.. _partial-flow-graph-scala:

Constructing and combining Partial Flow Graphs
----------------------------------------------
Sometimes it is not possible (or needed) to construct the entire computation graph in one place, but instead construct
all of it's different phases in different places and in the end connect them all into a complete graph and run it.

This can be achieved using :class:`PartialFlowGraph`. The reason of representing it as a different type is that a :class:`FlowGraph`
requires all ports to be connected, and if they're not it will throw an exception at construction time, which helps to avoid simple
wiring errors while working with graphs. A partial flow graph however does not perform this validation, and allows not fully connected graphs.

A :class:`PartialFlowGraphs` is defined as a :class:`FlowGraph` which contains so called "undefined elements",
such as ``UndefinedSink[T]`` or ``UndefinedSource[T]``, which can be reused and be "plugged into" by consumers of that
partial flow graph. Let's imagine we want to provide users with a specialized element that given 3 inputs will pick
the greatest int value of each zipped triple. We'll want to expose 3 input ports (undefined sources) and one output port
(undefined sink).

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#simple-partial-flow-graph

**TODO note to self - find better words instead of "element", it's not used both as "pipeline element" as well as stream element**

As you can see, first we construct the partial graph that will contains all the zipping and comparing of two stream
elements, then we import it (all of it's nodes and connections) explicitly to the :class:`FlowGraph` instance in which all
the undefined elements are rewired to real sources and sinks. The graph can then be run and yields the expected result.

.. warning::
  Please note that a :class:`FlowGraph` is not able to provide compile time type-safety about whether or not all
  elements have been properly connected - this validation is performed as a runtime check during the graph's instantiation.

Constructing Sources, Sinks and Flows from a Partial Graphs
-----------------------------------------------------------
Instead of treating a :class:`PartialFlowGraph` as simply a collection of flows and junctions, which may not yet all be
connected it is sometimes useful to expose such complex graph as a simpler structure,
such as a :class:`Source`, :class:`Sink` of :class:`Flow`.

In fact, these concepts can be easily expressed as special cases of a partially connected graph:

* :class:`Source` is a partial flow graph with *exactly one* :class:`UndefinedSink`,
* :class:`Sink` is a partial flow graph with *exactly one* :class:`UndefinedSource`,
* :class:`Flow` is a partial flow graph with *exactly one* :class:`UndefinedSource`, and *exactly one* :class:`UndefinedSource`.

Being able hide complex graphs inside of simple elements such as Sink / Source / Flow enables you to easily create one
complex element and from there on treat it as simple Source for linear computations.

In order to create a Source from a partial flow graph ``Source[T]`` provides a special apply method that takes a function
that must return an ``UndefinedSink[T]``. This undefined sink will become "the sink that must be attached before this Source
can run". Refer to the example below, in which we create a silly source that zips together two numbers, to see this graph
construction in action:

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#source-from-partial-flow-graph

Similarly the same can be done for a ``Sink[T]``, in which case the returned value must be an ``UndefinedSource[T]``.
For defining a ``Flow[T]`` we need to expose both an undefined source and sink:

.. includecode:: code/docs/stream/StreamPartialFlowGraphDocSpec.scala#flow-from-partial-flow-graph

Dealing with cycles, deadlocks
------------------------------
// TODO: why to avoid cycles, how to enable if you really need to

// TODO: problem cases, expand-conflate, expand-filter

// TODO: working with rate

// TODO: custom processing

// TODO: stages and flexi stuff

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


// TODO: Implementing Reactive Streams interfaces directly vs. extending ActorPublisher / ActorSubscriber???

Integrating with Actors
=======================

// TODO: Source.subscriber

// TODO: Sink.publisher

// TODO: Use the ImplicitFlowMaterializer if you have streams starting from inside actors.

// TODO: how do I create my own sources / sinks?

Integration with Reactive Streams enabled libraries
===================================================

// TODO: some info about reactive streams in general

// TODO: Simply runWith(Sink.publisher) and runWith(Source.subscriber) to get the corresponding reactive streams types.

