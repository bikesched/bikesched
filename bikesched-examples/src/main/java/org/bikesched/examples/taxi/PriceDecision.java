package org.bikesched.examples.taxi;

/**
 * The customer accepted or declined the driver's price: the payload of the {@value
 * TaxiBooking#PRICE_DECIDED} event.
 *
 * @param accepted whether the customer accepted the price
 */
public record PriceDecision(boolean accepted) {}
