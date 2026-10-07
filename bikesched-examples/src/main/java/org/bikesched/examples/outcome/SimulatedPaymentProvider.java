// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.outcome;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A payment provider with test cards in the style of real providers: one is charged, two are
 * declined, and one meets an outage on its first call (a technical error a retry fixes).
 */
public final class SimulatedPaymentProvider {

  /** Charged. */
  public static final String CARD_OK = "4242424242424242";

  /** Declined with {@code card_declined}. */
  public static final String CARD_DECLINED = "4000000000000002";

  /** Declined with {@code insufficient_funds}. */
  public static final String CARD_INSUFFICIENT_FUNDS = "4000000000009995";

  /** The first call fails with an {@link IOException}, the next one is charged. */
  public static final String CARD_OUTAGE_ONCE = "4000000000000119";

  private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

  /**
   * Charges a card.
   *
   * @param orderId the order, for the log and the call count
   * @param cardNumber the card to charge
   * @param amount the amount
   * @param idempotencyKey identifies repeated calls for the same charge
   * @return the receipt id
   * @throws CardDeclinedException if the card is declined
   * @throws IOException if the provider is unavailable
   */
  public String charge(String orderId, String cardNumber, BigDecimal amount, String idempotencyKey)
      throws CardDeclinedException, IOException {
    int call = calls.computeIfAbsent(orderId, _ -> new AtomicInteger()).incrementAndGet();
    System.out.println(
        "[payment]  charging " + amount + " for " + orderId + " (call " + call + ")");
    return switch (cardNumber) {
      case CARD_DECLINED -> throw new CardDeclinedException("card_declined", "Card was declined");
      case CARD_INSUFFICIENT_FUNDS ->
          throw new CardDeclinedException("insufficient_funds", "Insufficient funds");
      case CARD_OUTAGE_ONCE -> {
        if (call == 1) {
          throw new IOException("payment provider unavailable");
        }
        yield receipt();
      }
      default -> receipt();
    };
  }

  /**
   * Returns how often {@link #charge} was called for an order.
   *
   * @param orderId the order
   * @return the number of calls
   */
  public int calls(String orderId) {
    AtomicInteger count = calls.get(orderId);
    return count == null ? 0 : count.get();
  }

  private static String receipt() {
    return "rcpt_" + UUID.randomUUID().toString().substring(0, 8);
  }
}
