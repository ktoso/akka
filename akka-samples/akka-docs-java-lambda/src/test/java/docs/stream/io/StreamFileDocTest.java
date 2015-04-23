/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream.io;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import akka.actor.ActorSystem;
import akka.stream.ActorOperationAttributes;
import akka.stream.OperationAttributes;
import akka.stream.io.SynchronousFileSink;
import akka.stream.io.SynchronousFileSource;
import docs.stream.cookbook.RecipeParseLines;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.runtime.BoxedUnit;

import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.javadsl.StreamTcp.*;
import akka.stream.stage.*;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.ByteString;

public class StreamFileDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("StreamTcpDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  {
    //#file-source
    final File file = new File("example.csv");
    //#file-source
  }

  @Test
  public void demonstrateSimpleServerConnection() throws IOException {
    final File file = File.createTempFile(getClass().getName(), ".tmp");

    try {
      //#file-source
      SynchronousFileSource.create(file)
        .runForeach(chunk -> System.out.println(chunk.utf8String()), mat);
      //#file-source
    } finally {
      file.delete();
    }
  }

  @Test
  public void demonstrateSettingDispatchersInCode() throws IOException {
    final File file = File.createTempFile(getClass().getName(), ".tmp");

    try {
      //#custom-dispatcher-code
      SynchronousFileSink.create(file)
        .withAttributes(ActorOperationAttributes.dispatcher("custom-file-io-dispatcher"));
      //#custom-dispatcher-code
    } finally {
      file.delete();
    }
  }


}
