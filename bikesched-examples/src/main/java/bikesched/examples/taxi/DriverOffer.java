package bikesched.examples.taxi;

import java.math.BigDecimal;

/**
 * A driver accepted a ride request for this price: the payload of the {@value
 * TaxiBooking#DRIVER_ACCEPTED} event.
 *
 * @param driverId the driver
 * @param driverName the driver's name, shown to the customer
 * @param price the price of the ride
 * @param currency the currency of the price, e.g. {@code EUR}
 */
public record DriverOffer(String driverId, String driverName, BigDecimal price, String currency) {}
