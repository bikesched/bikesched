package org.bikesched.examples.taxi;

import java.math.BigDecimal;

/**
 * A reserved (not yet captured) payment for a ride.
 *
 * @param reservationId identifies the reservation at the payment provider
 * @param amount the reserved amount
 * @param currency the currency of the amount
 */
public record PaymentReservation(String reservationId, BigDecimal amount, String currency) {}
