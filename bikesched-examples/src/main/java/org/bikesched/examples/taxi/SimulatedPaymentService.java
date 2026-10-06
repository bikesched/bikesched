// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.taxi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** An in-memory payment provider, idempotent per reference. */
public final class SimulatedPaymentService implements PaymentService {

  private final Map<String, PaymentReservation> reservations = new ConcurrentHashMap<>();
  private final Set<String> released = ConcurrentHashMap.newKeySet();

  /** Creates the payment provider. */
  public SimulatedPaymentService() {}

  @Override
  public PaymentReservation reserve(
      String customerId, BigDecimal amount, String currency, String reference) {
    return reservations.computeIfAbsent(
        reference,
        _ -> {
          System.out.println(
              "[payment] reserved " + amount + " " + currency + " for customer " + customerId);
          return new PaymentReservation("reservation-" + reference, amount, currency);
        });
  }

  @Override
  public void release(PaymentReservation reservation) {
    if (released.add(reservation.reservationId())) {
      System.out.println(
          "[payment] released "
              + reservation.amount()
              + " "
              + reservation.currency()
              + " ("
              + reservation.reservationId()
              + ")");
    }
  }

  /**
   * Returns the ids of the released reservations.
   *
   * @return the released reservation ids
   */
  public Set<String> releasedReservations() {
    return Set.copyOf(released);
  }

  /**
   * Returns the reservations made.
   *
   * @return the reservations
   */
  public List<PaymentReservation> reservations() {
    return List.copyOf(reservations.values());
  }
}
