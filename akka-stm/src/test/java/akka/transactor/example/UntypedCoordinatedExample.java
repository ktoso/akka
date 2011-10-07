package akka.transactor.example;

import akka.AkkaApplication;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.dispatch.Future;
import akka.transactor.Coordinated;

public class UntypedCoordinatedExample {
    public static void main(String[] args) throws InterruptedException {
        System.out.println();
        System.out.println("Untyped transactor example");
        System.out.println();

        AkkaApplication application = new AkkaApplication("UntypedCoordinatedExample");

        ActorRef counter1 = application.createActor(new Props().withCreator(UntypedCoordinatedCounter.class));
        ActorRef counter2 = application.createActor(new Props().withCreator(UntypedCoordinatedCounter.class));

        counter1.tell(new Coordinated(new Increment(counter2)));

        Thread.sleep(3000);

        long timeout = 5000;

        Future future1 = counter1.ask("GetCount", timeout);
        Future future2 = counter2.ask("GetCount", timeout);

        future1.await();
        if (future1.isCompleted()) {
            if (future1.result().isDefined()) {
                int result = (Integer) future1.result().get();
                System.out.println("counter 1: " + result);
            }
        }

        future2.await();
        if (future2.isCompleted()) {
            if (future2.result().isDefined()) {
                int result = (Integer) future2.result().get();
                System.out.println("counter 2: " + result);
            }
        }

        counter1.stop();
        counter2.stop();
    }
}
