// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.poll;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bikesched.examples.poll.PollExample.PaymentSettled;
import org.bikesched.examples.poll.PollExample.Webhook;

/**
 * A payment provider that settles payments asynchronously: a created payment settles after a while
 * and the provider sends a webhook, unless the payment asks for the webhook to be lost. Its status
 * can be polled at any time.
 */
public final class SimulatedPaymentProvider {

  private final PrintStream out;
  private final Consumer<PaymentSettled> webhookEndpoint;
  private final Map<String, String> paymentsByIdempotencyKey = new ConcurrentHashMap<>();
  private final Map<String, PaymentSettled> settled = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> statusCalls = new ConcurrentHashMap<>();

  /**
   * Creates the provider.
   *
   * @param out where the provider prints its calls
   * @param webhookEndpoint receives the webhooks, as the application's webhook endpoint would
   */
  public SimulatedPaymentProvider(PrintStream out, Consumer<PaymentSettled> webhookEndpoint) {
    this.out = out;
    this.webhookEndpoint = webhookEndpoint;
  }

  /**
   * Creates a payment; it settles after {@code settlesAfter}. A repeated call with the same
   * idempotency key returns the payment created first.
   *
   * @param amount the amount
   * @param settlesAfter when the payment settles
   * @param webhook whether the webhook for the settled payment is delivered or lost
   * @param idempotencyKey identifies repeated calls for the same payment
   * @return the payment id
   */
  public String createPayment(
      BigDecimal amount, Duration settlesAfter, Webhook webhook, String idempotencyKey) {
    return paymentsByIdempotencyKey.computeIfAbsent(
        idempotencyKey,
        _ -> {
          String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 8);
          out.printf(
              "%s [provider] created %s for %s%n", PollExample.timestamp(), paymentId, amount);
          Thread.ofVirtual().start(() -> settle(paymentId, settlesAfter, webhook));
          return paymentId;
        });
  }

  private void settle(String paymentId, Duration settlesAfter, Webhook webhook) {
    try {
      Thread.sleep(settlesAfter);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    var payment = new PaymentSettled(paymentId, "succeeded");
    settled.put(paymentId, payment);
    if (webhook == Webhook.LOST) {
      out.printf("%s [provider] settled %s, webhook lost%n", PollExample.timestamp(), paymentId);
      return;
    }
    out.printf("%s [provider] settled %s, sending webhook%n", PollExample.timestamp(), paymentId);
    webhookEndpoint.accept(payment);
  }

  /**
   * Returns the payment once it settled.
   *
   * @param paymentId the payment
   * @return the settled payment, empty while it is pending
   */
  public Optional<PaymentSettled> status(String paymentId) {
    int call = statusCalls.computeIfAbsent(paymentId, _ -> new AtomicInteger()).incrementAndGet();
    Optional<PaymentSettled> payment = Optional.ofNullable(settled.get(paymentId));
    out.printf(
        "%s [provider] status of %s (call %d): %s%n",
        PollExample.timestamp(),
        paymentId,
        call,
        payment.map(PaymentSettled::status).orElse("pending"));
    return payment;
  }

  /**
   * Returns how often {@link #status} was called for a payment.
   *
   * @param paymentId the payment
   * @return the number of calls
   */
  public int statusCalls(String paymentId) {
    AtomicInteger count = statusCalls.get(paymentId);
    return count == null ? 0 : count.get();
  }
}
