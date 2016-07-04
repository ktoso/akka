/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.remote.artery.fastutil.booleans;

import akka.remote.artery.fastutil.objects.ObjectIterator;
import akka.remote.artery.fastutil.BidirectionalIterator;


/**
 * A type-specific bidirectional iterator; provides an additional method to avoid (un)boxing,
 * and the possibility to skip elements backwards.
 *
 * @see BidirectionalIterator
 */


public interface ObjectBidirectionalIterator<K> extends ObjectIterator<K>, BidirectionalIterator<K> {
  /**
   * Moves back for the given number of elements.
   * <p>
   * <P>The effect of this call is exactly the same as that of
   * calling {@link #previous()} for <code>n</code> times (possibly stopping
   * if {@link #hasPrevious()} becomes false).
   *
   * @param n the number of elements to skip back.
   * @return the number of elements actually skipped.
   * @see java.util.Iterator#next()
   */

  int back(int n);
}

