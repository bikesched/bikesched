// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.taxi;

/**
 * The taxi broker forwarding ride requests to its drivers. A driver accepting a request is reported
 * back asynchronously, e.g. by a webhook calling {@link TaxiBooking#driverAccepted}.
 */
public interface TaxiBroker {

  /**
   * Forwards the ride request to the drivers.
   *
   * @param ride the ride request
   */
  void requestRide(RideRequest ride);

  /**
   * Confirms the ride to the driver who accepted it, once the customer accepted the price and the
   * payment is reserved.
   *
   * @param rideId the ride
   * @param offer the accepted offer
   * @param reservation the reserved payment
   * @return the approval, with the estimated time of arrival
   */
  RideApproval approveRide(String rideId, DriverOffer offer, PaymentReservation reservation);

  /**
   * Withdraws the ride request or approval, e.g. because the customer declined the price or
   * cancelled the ride. Idempotent.
   *
   * @param rideId the ride
   */
  void cancelRide(String rideId);
}
