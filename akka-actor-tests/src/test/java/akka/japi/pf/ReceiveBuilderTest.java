/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.japi.pf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import scala.PartialFunction;
import scala.runtime.BoxedUnit;

public class ReceiveBuilderTest {
    @Test
    public void typed_receivebuilder_can_match_with_only_predicate_argument() {
      AtomicInteger matched = new AtomicInteger();
        
      PartialFunction<Object,BoxedUnit> pf = ReceiveBuilder
        .match(String::isEmpty, emptyString -> matched.incrementAndGet())
        .build();
      
      assertFalse(pf.isDefinedAt("42"));
      assertEquals(0, matched.get());
      
      assertTrue(pf.isDefinedAt(""));
      assertEquals(0, matched.get());
      
      pf.apply("");
      assertEquals(1, matched.get());
    }

}
