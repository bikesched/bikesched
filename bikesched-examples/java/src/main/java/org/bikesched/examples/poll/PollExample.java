// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.poll;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.event.TypedEvent;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.Poll;
import org.bikesched.job.TypedJob;

/**
 * A webhook with a polling fallback: the job creates a payment, waits a short time for the
 * provider's webhook, and polls the provider for the payment's status if the webhook does not
 * arrive (lost, or late). The webhook endpoint publishes each delivery as an event with the
 * delivery id as idempotency key, so a webhook the provider delivers twice is published once. Runs
 * against the Postgres from {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runPoll}; honors {@code POSTGRES_PORT} like
 * the compose file (default 5432).
 */
public final class PollExample {

  /** Takes a payment for an order. */
  public static final TypedJob<Payment> TAKE_PAYMENT = TypedJob.of("take-payment", Payment.class);

  /** The provider's webhook for a settled payment, keyed by the payment id. */
  public static final TypedEvent<PaymentSettled> PAYMENT_SETTLED =
      TypedEvent.of("payment-settled", PaymentSettled.class);

  /** How long the job waits for the webhook before it polls. */
  static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(2);

  /** The time between two status checks. */
  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  /** Whether the provider's webhook for a payment arrives. */
  public enum Webhook {
    /** The webhook is sent once the payment settled. */
    DELIVERED,
    /** The webhook is sent twice with the same delivery id, as providers retry deliveries. */
    DELIVERED_TWICE,
    /** The webhook is lost: only polling finds out that the payment settled. */
    LOST
  }

  /**
   * The job payload.
   *
   * @param orderId the order
   * @param amount the amount
   * @param settlesAfter when the provider settles the payment (simulated)
   * @param webhook whether the provider's webhook arrives (simulated)
   */
  public record Payment(
      String orderId, BigDecimal amount, Duration settlesAfter, Webhook webhook) {}

  /**
   * A settled payment, sent by the webhook and returned by the status call.
   *
   * @param paymentId the payment
   * @param status its status, e.g. {@code succeeded}
   */
  public record PaymentSettled(String paymentId, String status) {}

  /**
   * A webhook delivery: the provider repeats a delivery with the same id.
   *
   * @param deliveryId identifies the delivery
   * @param payment the settled payment
   */
  public record WebhookDelivery(String deliveryId, PaymentSettled payment) {}

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

  /**
   * The current time, formatted for the example's console output.
   *
   * @return the formatted time
   */
  static String timestamp() {
    return LocalTime.now().format(TIMESTAMP_FORMAT);
  }

  private final Bikesched bikesched;
  private final SimulatedPaymentProvider provider;

  PollExample(Bikesched bikesched, PrintStream out) {
    this.bikesched = bikesched;
    // The application's webhook endpoint publishes the webhook as an event, once per delivery id:
    // a repeated delivery returns the event published first.
    var provider =
        new SimulatedPaymentProvider(
            out,
            delivery -> {
              UUID eventId =
                  bikesched
                      .newEvent(PAYMENT_SETTLED, delivery.payment().paymentId())
                      .payload(delivery.payment())
                      .idempotencyKey(delivery.deliveryId())
                      .publish();
              out.printf(
                  "%s [shop]     webhook %s published as event %s%n",
                  timestamp(), delivery.deliveryId(), eventId);
            });
    this.provider = provider;

    bikesched.registerJobHandler(
        TAKE_PAYMENT,
        (job, payment) -> {
          // Step 1: create the payment; its id is the step's stored result, so later runs wait
          // for (and poll) the same payment.
          String paymentId =
              job.step(
                  "create-payment",
                  String.class,
                  step ->
                      provider.createPayment(
                          payment.amount(),
                          payment.settlesAfter(),
                          payment.webhook(),
                          step.jobId() + ":" + step.name()));

          // Step 2: wait for the webhook, at most WEBHOOK_TIMEOUT.
          PaymentSettled webhook =
              job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
                  .timeout(WEBHOOK_TIMEOUT)
                  .onFailure(failing -> failing.resolve(null)) // no webhook: poll instead
                  .receive();

          // Step 3, only without the webhook: poll the provider until the payment settled. Each
          // check that is still pending suspends the job until the next one.
          PaymentSettled settled =
              webhook != null
                  ? webhook
                  : job.newPoll("poll-payment", PaymentSettled.class, POLL_INTERVAL)
                      .timeout(Duration.ofSeconds(30))
                      .run(
                          ignored ->
                              provider
                                  .status(paymentId)
                                  .map(Poll::ready)
                                  .orElseGet(Poll::notReady));

          // Step 4: mark the order paid.
          job.step(
              "mark-paid",
              Void.class,
              ignored -> {
                out.printf(
                    "%s [shop]     order %s paid (%s, %s)%n",
                    timestamp(),
                    payment.orderId(),
                    settled.paymentId(),
                    webhook != null ? "webhook" : "polled");
                return null;
              });
        });
  }

  /**
   * Schedules taking a payment.
   *
   * @param orderId the order
   * @param settlesAfter when the provider settles the payment
   * @param webhook whether the provider's webhook arrives
   * @return the job id
   */
  public UUID pay(String orderId, Duration settlesAfter, Webhook webhook) {
    return bikesched
        .newJob(TAKE_PAYMENT)
        .payload(new Payment(orderId, new BigDecimal("42.00"), settlesAfter, webhook))
        .schedule();
  }

  /** The provider, e.g. to count its status calls. */
  SimulatedPaymentProvider provider() {
    return provider;
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("poll-example")) {
      var example = new PollExample(bikesched, System.out);

      List<UUID> jobIds =
          List.of(
              // Settles within the webhook timeout: the webhook arrives, no polling.
              example.pay("order-webhook", Duration.ofSeconds(1), Webhook.DELIVERED),
              // The webhook arrives twice: the event is published once.
              example.pay("order-webhook-twice", Duration.ofSeconds(1), Webhook.DELIVERED_TWICE),
              // Settles after the webhook timeout, and the webhook is lost: polling finds it.
              example.pay("order-polled", Duration.ofSeconds(4), Webhook.LOST));

      for (UUID jobId : jobIds) {
        JobState state =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
        System.out.println(timestamp() + " [app]      job " + jobId + " ended " + state);
      }
    }
  }
}
