package org.bikesched.examples.cinema;

import java.math.BigDecimal;

/**
 * A charged payment: the result of the {@code charge-payment} step.
 *
 * @param paymentId identifies the payment at the payment provider
 * @param amount the charged amount in EUR
 */
public record PaymentReceipt(String paymentId, BigDecimal amount) {}
