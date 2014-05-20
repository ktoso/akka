.. _migration-guide-persistence-experimental-2.3.x:

#####################################################
Migration Guide Akka Persistence (experimental) 2.3.3
#####################################################

**Akka Persistence** is an **experimental module**, which means that neither Binary Compatibility nor API stability
is provided for Persistence while under the *experimental* flag. The goal of this phase is to gather user feedback
before we freeze the APIs in a major release.

Removed Processor in favour of extending EventsourcedProcessor with persistAsync (2.3.4)
========================================================================================

The ``Processor`` is now deprecated and will be removed soon.
It's semantics replicated in ``EventsourcedProcessor`` in the form of an additional ``persit`` method: ``persistAsync``.

In essence, the difference betwen ``persist`` and ``persistAsync`` is that the former will stash all incomming commands
until all persist callbacks have ben processed, whereas the latter does not stash any commands. The new ``persistAsync``
should be used in cases of low consistency yet high responsiveness requirements, the Actor can keep processing incomming
commands, even though not all previous events have been handled.

When these ``persist`` and ``persistAsync`` are used together in the same ``EventsourcedProcessor``, the ``persist``
logic will win over the async version so that all guarantees concerning persist still hold. This will however lower
the throughput

Now deprecated code using Processor::

    class OldProcessor extends Processor {
      def receive = {
        case Persistent(cmd) => sender() ! cmd
      }
    }

Replacement code, with the same semantics, using EventsourcedProcessor::

    class OldProcessor extends Processor {
      def receiveCommand = {
        case cmd =>
          persistAsync(cmd) { e => sender() ! e }
      }

      def receiveEvent = {
        case _ => // logic for handling replay
      }
    }

It is worth pointing out that using ``sender()`` inside the persistAsync callback block is **valid**, and does *not* suffer
any of the problems Futures have when closing over the sender reference.