// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.publish;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.Bikesched;
import org.bikesched.event.TypedEvent;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.TypedJob;

/**
 * A job publishes an event exactly once as a durable step ({@code job.newEvent(…).publish()}), and
 * another job waits for it. Runs against the Postgres from {@code compose.yaml} ({@code docker
 * compose up -d}).
 *
 * <p>The {@code ship-order} job hands the order over to the carrier, publishes {@code
 * order-shipped} and then sleeps until the parcel was picked up. After the sleep its handler runs
 * again from the start: the publish finds its step and returns the same event id without publishing
 * again. The {@code notify-customer} job waits for the event and mails the tracking id.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runPublish}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class PublishExample {

  /** Ships an order and publishes {@link #ORDER_SHIPPED}. */
  public static final TypedJob<ShipOrder> SHIP_ORDER = TypedJob.of("ship-order", ShipOrder.class);

  /** Waits for {@link #ORDER_SHIPPED} and mails the customer. */
  public static final TypedJob<NotifyCustomer> NOTIFY_CUSTOMER =
      TypedJob.of("notify-customer", NotifyCustomer.class);

  /** Published by the shipping job, keyed by the order id. */
  public static final TypedEvent<OrderShipped> ORDER_SHIPPED =
      TypedEvent.of("order-shipped", OrderShipped.class);

  /**
   * The payload of the shipping job.
   *
   * @param orderId the order to ship
   */
  public record ShipOrder(String orderId) {}

  /**
   * The payload of the notification job.
   *
   * @param orderId the order whose shipment to wait for
   * @param email the customer's address
   */
  public record NotifyCustomer(String orderId, String email) {}

  /**
   * The event payload.
   *
   * @param orderId the shipped order, also the event's key
   * @param trackingId the carrier's tracking id
   */
  public record OrderShipped(String orderId, String trackingId) {}

  /**
   * The jobs of one order.
   *
   * @param shipJobId the shipping job
   * @param notifyJobId the notification job
   */
  public record OrderJobs(UUID shipJobId, UUID notifyJobId) {}

  private final Bikesched bikesched;
  private final List<UUID> publishedEventIds = new CopyOnWriteArrayList<>();

  PublishExample(Bikesched bikesched) {
    this.bikesched = bikesched;

    bikesched.registerJobHandler(
        SHIP_ORDER,
        (job, order) -> {
          // Step 1: hand the parcel over to the carrier; the tracking id is the step's result.
          String trackingId =
              job.step(
                  "hand-over-to-carrier",
                  String.class,
                  ignored -> {
                    String id = "TRACK-" + UUID.randomUUID().toString().substring(0, 8);
                    System.out.println(
                        "[shipping] order " + order.orderId() + " handed over: " + id);
                    return id;
                  });

          // Step 2: publish the event. The event and the completed step commit in one
          // transaction; later runs of this handler return the same event id without publishing.
          UUID eventId =
              job.newEvent(ORDER_SHIPPED, order.orderId())
                  .payload(new OrderShipped(order.orderId(), trackingId))
                  .publish();
          publishedEventIds.add(eventId);
          System.out.println("[shipping] publish() returned event " + eventId);

          // Step 3: sleep until the parcel was picked up; the handler then runs again from the
          // start, and steps 1 and 2 return their stored results.
          job.sleep("await-pickup", Duration.ofSeconds(1));

          job.step(
              "close-order",
              Void.class,
              ignored -> {
                System.out.println("[shipping] order " + order.orderId() + " closed");
                return null;
              });
        });

    bikesched.registerJobHandler(
        NOTIFY_CUSTOMER,
        (job, notify) -> {
          // Suspends the job without a thread until the event is published (or receives it right
          // away if it already was).
          OrderShipped shipped = job.awaitEvent("await-shipment", ORDER_SHIPPED, notify.orderId());
          job.step(
              "send-mail",
              Void.class,
              ignored -> {
                System.out.println(
                    "[mail]     to "
                        + notify.email()
                        + ": your order "
                        + shipped.orderId()
                        + " is on its way, tracking id "
                        + shipped.trackingId());
                return null;
              });
        });
  }

  /**
   * Schedules the notification and the shipping job of a new order.
   *
   * @param orderId the order id, the key of its event
   * @param email the customer's address
   * @return the ids of both jobs
   */
  public OrderJobs placeOrder(String orderId, String email) {
    UUID notifyJobId =
        bikesched.newJob(NOTIFY_CUSTOMER).payload(new NotifyCustomer(orderId, email)).schedule();
    UUID shipJobId = bikesched.newJob(SHIP_ORDER).payload(new ShipOrder(orderId)).schedule();
    return new OrderJobs(shipJobId, notifyJobId);
  }

  /**
   * Returns what {@code publish()} returned on each run of the shipping handler, in order.
   *
   * @return the event ids; the same id once per run
   */
  public List<UUID> publishedEventIds() {
    return List.copyOf(publishedEventIds);
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("publish-example")) {
      var example = new PublishExample(bikesched);

      OrderJobs jobs = example.placeOrder("order-" + UUID.randomUUID(), "alice@example.com");

      for (UUID jobId : List.of(jobs.shipJobId(), jobs.notifyJobId())) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      }
      System.out.println("[app]      publish() results: " + example.publishedEventIds());
    }
  }
}
