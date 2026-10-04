package org.bikesched;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Starts bikesched against the Postgres from {@code compose.yaml} ({@code docker compose up -d}),
 * registers a {@code print} job handler, schedules a few demo jobs (now and in 15 seconds) and
 * keeps the node running until the JVM is stopped, e.g. with Ctrl+C.
 *
 * <p>Run with {@code ./gradlew :bikesched-core:runStarter} or {@code ... runStarter --args=my-node}
 * to set the node name (default: host name). Honors {@code POSTGRES_PORT} like the compose file
 * (default 5432).
 */
public final class Starter {

  private Starter() {}

  static void main(String[] args) throws InterruptedException {
    var dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[] {"localhost"});
    dataSource.setPortNumbers(
        new int[] {Integer.parseInt(System.getenv().getOrDefault("POSTGRES_PORT", "5432"))});
    dataSource.setDatabaseName("bikesched");
    dataSource.setUser("bikesched");
    dataSource.setPassword("bikesched");

    var builder = Bikesched.builder().dataSource(dataSource).autoStartPolling(false);
    if (args.length > 0) {
      builder.nodeName(args[0]);
    }
    Bikesched bikesched = builder.build();
    Runtime.getRuntime().addShutdownHook(new Thread(bikesched::close));

    bikesched.registerJobHandler(
        "print",
        job -> {
          System.out.println(
              "["
                  + Thread.currentThread().getName()
                  + "] printing job "
                  + job.id()
                  + " (attempt "
                  + job.attempts()
                  + "/"
                  + job.maxAttempts()
                  + "): "
                  + job.payload());
          int randomOneOrZero = (int) (Math.random() * 2);
          if (randomOneOrZero == 1) throw new RuntimeException("random failure");
        });

    Node node = bikesched.startPolling();
    System.out.println(
        "bikesched node '"
            + node.name()
            + "' ("
            + node.id().orElseThrow()
            + ") running, press Ctrl+C to stop");

    bikesched
        .newJob("event waiter")
        .payload("{\"approval\": 1}")
        .handler(
            job -> {
              JsonObject jobPayload = JsonParser.object().from(job.payload());
              Integer approvalId = (Integer) jobPayload.get("approval");

              var eventJson =
                  job.waitForEvent("wait for approval", "approval", approvalId.toString())
                      .receive(event -> "{\"approval\": " + event.key() + "}");
              JsonObject event = JsonParser.object().from(eventJson);
              Integer approval = (Integer) event.get("approval");
              System.out.println("Approval received: " + approval);
            })
        .schedule();

    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    bikesched.newEvent("approval", "1").publish();

    // Polling was not started on build(): start it now that all handlers are registered.
    //    for (int i = 1; i <= 3; i++) {
    //      bikesched.newJob("print").payload("{\"n\": " + i + "}").schedule();
    //    }
    //
    //    bikesched.newJob("print").payload("{\"delayed\": true}").schedule(Duration.ofSeconds(15));
    //
    //    bikesched.newJob("cancel").handler(job -> job.cancel("I canceled it myself!")).schedule();
    //
    //    bikesched
    //        .newJob("with-steps")
    //        .payload("{\"steps\": true}")
    //        .handler(
    //            job -> {
    //              System.out.println("Job payload: " + job.payload());
    //              var step1 = job.step("Step 1", String.class, step -> "{\"step\": 1}");
    //              System.out.println("Step 1 result: " + step1);
    //              var step2 = job.step("Step 2", String.class, step -> "{\"step\": 2}");
    //              System.out.println("Step 2 result: " + step2);
    //            })
    //        .schedule();
    //
    //    var retries = new AtomicInteger(0);
    //    bikesched
    //        .newJob("with retry")
    //        .handler(
    //            job -> {
    //              var retry = retries.getAndIncrement();
    //              System.out.println("Job with retry: " + retry);
    //              if (retry == 0) {
    //                job.fail().remainingAttempts(10);
    //              } else if (retry == 10) {
    //              } else {
    //                job.fail().retryAfter(Duration.ofMillis(retry * 250));
    //              }
    //            })
    //        .maxAttempts(5)
    //        .schedule();

    Thread.currentThread().join();
  }
}
