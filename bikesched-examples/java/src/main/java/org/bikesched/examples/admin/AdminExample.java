// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.admin;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.bikesched.Bikesched;
import org.bikesched.admin.web.BikeschedAdmin;
import org.bikesched.event.EventType;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobResult;
import org.bikesched.job.JobType;
import org.bikesched.job.Recurrence;

/**
 * The admin UI next to a few demo jobs: invoices (some fail), orders with a child job and a wait
 * for the payment event, parcels, and an inventory sync recurring every 10 seconds. Serves the page
 * with the JDK's {@link HttpServer}; the node records the job history, so every job detail shows
 * it.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runAdmin} against the Postgres from {@code
 * compose.yaml}, then open http://localhost:8081/bikesched/ ({@code ADMIN_PORT} changes the port).
 */
public final class AdminExample {

  public record Invoice(int number, String customer) {}

  public record Order(int number) {}

  public record Parcel(int order) {}

  public record Payment(int amount) {}

  public static final JobType<Invoice> SEND_INVOICE = JobType.of("send-invoice", Invoice.class);

  public static final JobType<Order> FULFIL_ORDER = JobType.of("fulfil-order", Order.class);

  public static final JobType<Parcel> SHIP_PARCEL = JobType.of("ship-parcel", Parcel.class);

  public static final EventType<Payment> PAYMENT_RECEIVED =
      EventType.of("payment-received", Payment.class);

  /** The recurring job's type: two steps, no waits. */
  public static final String SYNC_INVENTORY = "sync-inventory";

  /** How often the inventory sync runs. */
  public static final Recurrence EVERY_10_SECONDS = Recurrence.every(Duration.ofSeconds(10));

  /** How long the job history is kept, so the job detail has a History tab. */
  public static final Duration JOB_HISTORY = Duration.ofDays(7);

  private final Bikesched bikesched;

  AdminExample(Bikesched bikesched) {
    this.bikesched = bikesched;
    bikesched.registerJobHandler(
        SEND_INVOICE,
        (job, invoice) -> {
          job.step("render-pdf", String.class, ignored -> "invoice-" + invoice.number() + ".pdf");
          if (invoice.number() % 4 == 0) {
            job.fail("SMTP timeout while sending invoice " + invoice.number());
          }
        });
    bikesched.registerJobHandler(
        FULFIL_ORDER,
        (job, order) -> {
          job.step("reserve-stock", Boolean.class, ignored -> true);
          var parcel =
              job.newJob(SHIP_PARCEL)
                  .stepName("ship")
                  .payload(new Parcel(order.number()))
                  .schedule();
          if (!(job.waitForJob(parcel, String.class) instanceof JobResult.Succeeded<String>)) {
            job.fail("shipping failed");
          }
          job.waitForEvent("await-payment", PAYMENT_RECEIVED, "order-" + order.number())
              .timeout(Duration.ofHours(1))
              .receive();
        });
    bikesched.registerJobHandler(
        SHIP_PARCEL,
        (job, parcel) -> {
          job.sleep("pick-up", Duration.ofSeconds(2));
          job.complete("tracking-" + parcel.order());
        });
    bikesched.registerJobHandler(
        SYNC_INVENTORY,
        job -> {
          int items = job.step("count-stock", Integer.class, ignored -> 120);
          job.step("update-shop", String.class, ignored -> items + " items in stock");
        });
  }

  /**
   * Schedules the demo jobs, pays half of the orders and registers the inventory sync, whose first
   * run is now.
   */
  void seed() {
    bikesched.newJob(SYNC_INVENTORY).schedule(EVERY_10_SECONDS, Duration.ZERO);
    for (int number = 1; number <= 8; number++) {
      bikesched.newJob(SEND_INVOICE).payload(new Invoice(number, "customer-" + number)).schedule();
    }
    for (int number = 1; number <= 4; number++) {
      bikesched.newJob(FULFIL_ORDER).payload(new Order(number)).schedule();
    }
    for (int number = 1; number <= 2; number++) {
      bikesched.newEvent(PAYMENT_RECEIVED, "order-" + number).payload(new Payment(42)).publish();
    }
  }

  /**
   * Serves the admin under {@code /bikesched}. The demo data holds no secrets, so payloads are
   * shown; they are hidden by default.
   */
  HttpServer serve(int port) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
      server.createContext(
          "/bikesched", BikeschedAdmin.of(bikesched).showPayloads(true).httpHandler());
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void main() throws InterruptedException {
    int port = Integer.parseInt(System.getenv().getOrDefault("ADMIN_PORT", "8081"));
    try (Bikesched bikesched =
        BikeschedUtil.builder("admin-example").jobHistory(JOB_HISTORY).build()) {
      var example = new AdminExample(bikesched);
      example.seed();
      HttpServer server = example.serve(port);
      System.out.println("bikesched admin: http://localhost:" + port + "/bikesched/ (Ctrl+C ends)");
      var stopped = new CountDownLatch(1);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    server.stop(0);
                    stopped.countDown();
                  }));
      stopped.await();
    }
  }
}
