package akka.stream.javadsl;

import scala.collection.immutable.Seq;
import scala.runtime.BoxedUnit;

import java.util.Arrays;

class JSourceBase {
  // TODO: this is a workaround for SI-8743 and Scala 2.10.5; remove once 2.10 not supported
  @SuppressWarnings("unchecked")
  public <T> Source<T, BoxedUnit> elements(final T... elems) {
    final Seq<T> seq = HorribleHack.immutableSeq(Arrays.asList(elems).iterator());
    return new Source<T, BoxedUnit>(akka.stream.scaladsl.Source.apply(HorribleHack.function(seq.iterator())));
  }
}