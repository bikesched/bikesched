// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.outcome;

import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;

/**
 * Business outcomes as step results: a declined card is an expected outcome, returned as {@link
 * ChargeOutcome.Declined} and stored like a success, while an outage of the payment provider is a
 * technical error that fails the step, so the job is retried. Runs against the Postgres from {@code
 * compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runOutcome}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class OutcomeExample {

  /** Charges an order's card and tells the customer. */
  public static final JobType<ChargeOrder> CHARGE_ORDER =
      JobType.of("charge-order", ChargeOrder.class);

  /**
   * The job payload.
   *
   * @param orderId the order
   * @param email the customer's address
   * @param amount the amount to charge
   * @param cardNumber the card to charge
   */
  public record ChargeOrder(String orderId, String email, BigDecimal amount, String cardNumber) {}

  private final Bikesched bikesched;

  OutcomeExample(Bikesched bikesched, SimulatedPaymentProvider provider) {
    this.bikesched = bikesched;

    bikesched.registerJobHandler(
        CHARGE_ORDER,
        (job, order) -> {
          // Step 1: charge the card. A decline is a business outcome: returned and stored as the
          // step's result, so it is not retried. Other exceptions (the provider's IOException)
          // fail the step, and the job is retried.
          ChargeOutcome outcome =
              job.step(
                  "charge-card",
                  ChargeOutcome.class,
                  step -> {
                    try {
                      String receiptId =
                          provider.charge(
                              order.orderId(),
                              order.cardNumber(),
                              order.amount(),
                              step.jobId() + ":" + step.name());
                      return new ChargeOutcome.Charged(receiptId);
                    } catch (CardDeclinedException e) {
                      return new ChargeOutcome.Declined(e.code(), e.getMessage());
                    }
                  });

          // Step 2: branch on the stored outcome; every later run takes the same branch.
          switch (outcome) {
            case ChargeOutcome.Charged charged ->
                job.step(
                    "send-receipt",
                    Void.class,
                    _ -> {
                      System.out.println(
                          "[mail]     to "
                              + order.email()
                              + ": paid "
                              + order.amount()
                              + ", receipt "
                              + charged.receiptId());
                      return null;
                    });
            case ChargeOutcome.Declined declined -> {
              job.step(
                  "send-decline",
                  Void.class,
                  _ -> {
                    System.out.println(
                        "[mail]     to "
                            + order.email()
                            + ": payment declined ("
                            + declined.reason()
                            + "), please use another card");
                    return null;
                  });
              job.cancel("payment declined: " + declined.code());
            }
          }
        });
  }

  /**
   * Schedules charging an order.
   *
   * @param orderId the order
   * @param cardNumber the card to charge, see the {@link SimulatedPaymentProvider} test cards
   * @return the job id
   */
  public UUID chargeOrder(String orderId, String cardNumber) {
    return bikesched
        .newJob(CHARGE_ORDER)
        .payload(new ChargeOrder(orderId, "alice@example.com", new BigDecimal("42.00"), cardNumber))
        .schedule();
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("outcome-example")) {
      var example = new OutcomeExample(bikesched, new SimulatedPaymentProvider());

      List<UUID> jobIds =
          List.of(
              example.chargeOrder("order-ok", SimulatedPaymentProvider.CARD_OK),
              example.chargeOrder(
                  "order-declined", SimulatedPaymentProvider.CARD_INSUFFICIENT_FUNDS),
              example.chargeOrder("order-outage", SimulatedPaymentProvider.CARD_OUTAGE_ONCE));

      for (UUID jobId : jobIds) {
        JobState state =
            await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
        System.out.println("[app]      job " + jobId + " ended " + state);
      }
    }
  }
}
