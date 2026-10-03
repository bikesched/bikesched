package bikesched.examples.taxi;

import bikesched.core.Bikesched;
import bikesched.examples.JacksonJsonMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Books a taxi ride end to end against the Postgres from {@code compose.yaml} ({@code docker
 * compose up -d}), playing the driver and the customer with simulated services.
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:run}; honors {@code POSTGRES_PORT} like the
 * compose file (default 5432).
 */
public final class TaxiBookingExample {

  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

  private TaxiBookingExample() {}

  static void main() throws InterruptedException {
    var broker = new SimulatedTaxiBroker();
    var payments = new SimulatedPaymentService();
    var notifications = new SimulatedPushNotifications();

    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(composeDatabase())
            .node("taxi-example")
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

      // 1. The customer requests a ride.
      var ride =
          new RideRequest(UUID.randomUUID().toString(), "customer-42", "Main Station", "Airport");
      booking.requestRide(ride);

      // 2. A driver gets the request from the broker and accepts it with a price.
      RideRequest request = broker.nextRequest(ANSWER_TIMEOUT);
      booking.driverAccepted(
          request.rideId(), new DriverOffer("driver-7", "Anna", new BigDecimal("34.50"), "EUR"));

      // 3. The customer is asked to accept the price, and does.
      notifications.next(ANSWER_TIMEOUT);
      booking.priceDecided(ride.rideId(), true);

      // 4.-6. bikesched reserves the payment, approves the ride at the broker and sends the
      // customer the estimated time of arrival.
      notifications.next(ANSWER_TIMEOUT);
    }
  }

  private static PGSimpleDataSource composeDatabase() {
    var dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[] {"localhost"});
    dataSource.setPortNumbers(
        new int[] {Integer.parseInt(System.getenv().getOrDefault("POSTGRES_PORT", "5432"))});
    dataSource.setDatabaseName("bikesched");
    dataSource.setUser("bikesched");
    dataSource.setPassword("bikesched");
    return dataSource;
  }
}
