// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.taxi;

import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.DatabaseUtil;
import org.bikesched.jackson.JacksonJsonMapper;

/**
 * Books taxi rides end to end against the Postgres from {@code compose.yaml} ({@code docker compose
 * up -d}), playing the driver and the customer with simulated services:
 *
 * <ol>
 *   <li>a ride is booked: driver and customer accept, the payment is reserved, the ride approved;
 *   <li>the customer cancels the ride while asked for the price: the compensation withdraws the
 *       ride at the broker;
 *   <li>the broker fails to approve the ride: once the job fails permanently, the compensations
 *       release the payment and withdraw the ride, latest step first.
 * </ol>
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:run}; honors {@code POSTGRES_PORT} like the
 * compose file (default 5432).
 */
public final class TaxiBookingExample {

  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

  private static final DriverOffer OFFER =
      new DriverOffer("driver-7", "Anna", new BigDecimal("34.50"), "EUR");

  private TaxiBookingExample() {}

  static void main() throws InterruptedException {
    var broker = new SimulatedTaxiBroker();
    var payments = new SimulatedPaymentService();
    var notifications = new SimulatedPushNotifications();

    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DatabaseUtil.composeDatabase())
            .nodeName("taxi-example")
            .jsonMapper(new JacksonJsonMapper())
            .build()) {

      TaxiBooking booking =
          TaxiBooking.register(
              bikesched,
              broker,
              payments,
              notifications,
              Duration.ofMinutes(5),
              Duration.ofMinutes(2));

      bookRide(booking, broker, notifications);
      customerCancelsRide(booking, broker, notifications);
      brokerFailsToApproveRide(booking, broker, payments, notifications);
    }
  }

  /** The happy path: the ride is booked and the customer gets the estimated time of arrival. */
  private static void bookRide(
      TaxiBooking booking, SimulatedTaxiBroker broker, SimulatedPushNotifications notifications)
      throws InterruptedException {
    System.out.println("\n=== Ride 1: booked ===");

    // 1. The customer requests a ride.
    RideRequest ride = newRide();
    booking.requestRide(ride);

    // 2. A driver gets the request from the broker and accepts it with a price.
    broker.nextRequest(ANSWER_TIMEOUT);
    booking.driverAccepted(ride.rideId(), OFFER);

    // 3. The customer is asked to accept the price, and does.
    notifications.next(ANSWER_TIMEOUT);
    booking.priceDecided(ride.rideId(), true);

    // 4.-6. bikesched reserves the payment, approves the ride at the broker and sends the
    // customer the estimated time of arrival.
    notifications.next(ANSWER_TIMEOUT);
  }

  /**
   * Compensation after a cancellation: the customer cancels while the job waits for the price
   * decision. Only "send-to-broker" completed with a compensation, so the ride is withdrawn at the
   * broker; no payment was reserved.
   */
  private static void customerCancelsRide(
      TaxiBooking booking, SimulatedTaxiBroker broker, SimulatedPushNotifications notifications)
      throws InterruptedException {
    System.out.println("\n=== Ride 2: cancelled by the customer ===");

    RideRequest ride = newRide();
    UUID jobId = booking.requestRide(ride);
    broker.nextRequest(ANSWER_TIMEOUT);
    booking.driverAccepted(ride.rideId(), OFFER);
    notifications.next(ANSWER_TIMEOUT); // the price question, which the customer does not answer

    System.out.println("[app] customer cancels the ride");
    booking.cancelRide(jobId);

    await("the ride is withdrawn")
        .atMost(ANSWER_TIMEOUT)
        .until(() -> broker.cancelledRides().contains(ride.rideId()));
  }

  /**
   * Compensation after a permanent failure: the payment is reserved, but the broker cannot approve
   * the ride. Once the job failed its last attempt, the compensations run latest step first: the
   * payment is released, then the ride is withdrawn at the broker.
   */
  private static void brokerFailsToApproveRide(
      TaxiBooking booking,
      SimulatedTaxiBroker broker,
      SimulatedPaymentService payments,
      SimulatedPushNotifications notifications)
      throws InterruptedException {
    System.out.println("\n=== Ride 3: the broker fails to approve it ===");
    broker.failApprovals(true);
    try {
      RideRequest ride = newRide();
      booking.requestRide(ride);
      broker.nextRequest(ANSWER_TIMEOUT);
      booking.driverAccepted(ride.rideId(), OFFER);
      notifications.next(ANSWER_TIMEOUT);
      booking.priceDecided(ride.rideId(), true);

      // Approving fails in all 3 attempts (see the WARN/ERROR log), then the job is compensated.
      await("the ride is withdrawn after releasing the payment")
          .atMost(ANSWER_TIMEOUT)
          .until(() -> broker.cancelledRides().contains(ride.rideId()));
      System.out.println(
          "[example] released reservations: "
              + payments.releasedReservations().stream()
                  .filter(id -> id.endsWith(ride.rideId()))
                  .toList());
    } finally {
      broker.failApprovals(false);
    }
  }

  private static RideRequest newRide() {
    return new RideRequest(UUID.randomUUID().toString(), "customer-42", "Main Station", "Airport");
  }
}
