package org.bikesched.examples.taxi;

import java.math.BigDecimal;

/** The payment provider. */
public interface PaymentService {

  /**
   * Reserves the amount on the customer's payment method. Idempotent per reference: bikesched
   * executes steps at least once, so a step interrupted by a crash reserves again.
   *
   * @param customerId the customer
   * @param amount the amount to reserve
   * @param currency the currency of the amount
   * @param reference identifies the reservation for idempotency, e.g. the ride id
   * @return the reservation
   */
  PaymentReservation reserve(
      String customerId, BigDecimal amount, String currency, String reference);

  /**
   * Releases a reservation, e.g. because the ride was cancelled. Idempotent.
   *
   * @param reservation the reservation
   */
  void release(PaymentReservation reservation);
}
