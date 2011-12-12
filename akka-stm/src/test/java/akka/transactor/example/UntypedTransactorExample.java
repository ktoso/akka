package akka.transactor.example;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Block;
import akka.dispatch.Future;
import akka.testkit.AkkaSpec;
import akka.util.Duration;

import java.util.concurrent.TimeUnit;

public class UntypedTransactorExample {
  public static void main(String[] args) throws InterruptedException {

    ActorSystem app = ActorSystem.create("UntypedTransactorExample", AkkaSpec.testConf());

    ActorRef counter1 = app.actorOf(new Props().withCreator(UntypedCounter.class));
    ActorRef counter2 = app.actorOf(new Props().withCreator(UntypedCounter.class));

    counter1.tell(new Increment(counter2));

    Thread.sleep(3000);

    long timeout = 5000;
    Duration d = Duration.create(timeout, TimeUnit.MILLISECONDS);

    Future future1 = counter1.ask("GetCount", timeout);
    Future future2 = counter2.ask("GetCount", timeout);

    int count1 = (Integer)Block.sync(future1, d);
    System.out.println("counter 1: " + count1);
    int count2 = (Integer)Block.sync(future2, d);
    System.out.println("counter 1: " + count2);

    app.stop();
  }
}
