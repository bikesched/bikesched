package org.bikesched.examples.taxi;

/**
 * A customer's request for a ride: the payload of the ride's job.
 *
 * @param rideId identifies the ride, also the key of the ride's events
 * @param customerId the customer who requested the ride
 * @param pickup where to pick up the customer
 * @param destination where to go
 */
public record RideRequest(String rideId, String customerId, String pickup, String destination) {}
