package bikesched.examples.taxi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import bikesched.core.Bikesched;
import bikesched.examples.JacksonJsonMapper;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the taxi booking against a real PostgreSQL. */
class TaxiBookingTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();
  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(10);

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  private final SimulatedTaxiBroker broker = new SimulatedTaxiBroker();
  private final SimulatedPaymentService payments = new SimulatedPaymentService();
  private final SimulatedPushNotifications notifications = new SimulatedPushNotifications();
  private final RideRequest ride =
      new RideRequest(UUID.randomUUID().toString(), "customer-1", "Main Station", "Airport");
  private final DriverOffer offer =
      new DriverOffer("driver-7", "Anna", new BigDecimal("34.50"), "EUR");
  private Bikesched bikesched;

  @AfterEach
  void close() {
    bikesched.close();
  }

  @Test
  void rideIsBookedOnceADriverAndTheCustomerAccepted() throws Exception {
    TaxiBooking booking = start(Duration.ofMinutes(1));
    UUID jobId = booking.requestRide(ride);

    assertThat(broker.nextRequest(ANSWER_TIMEOUT)).isEqualTo(ride);
    booking.driverAccepted(ride.rideId(), offer);

    assertThat(notifications.next(ANSWER_TIMEOUT).message())
        .isEqualTo("Anna can take you to Airport for 34.50 EUR. Accept?");
    booking.priceDecided(ride.rideId(), true);

    assertThat(notifications.next(ANSWER_TIMEOUT))
        .isEqualTo(
            new SimulatedPushNotifications.Notification(
                "customer-1", "Anna is on the way and arrives in about 7 minutes."));
    awaitState(jobId, "SUCCEEDED");
    assertThat(payments.reservations())
        .containsExactly(
            new PaymentReservation("reservation-" + ride.rideId(), new BigDecimal("34.50"), "EUR"));
    assertThat(broker.approvedRides()).containsExactly(ride.rideId());
  }

  @Test
  void rideIsCancelledIfTheCustomerDeclinesThePrice() throws Exception {
    TaxiBooking booking = start(Duration.ofMinutes(1));
    UUID jobId = booking.requestRide(ride);
    broker.nextRequest(ANSWER_TIMEOUT);
    booking.driverAccepted(ride.rideId(), offer);
    notifications.next(ANSWER_TIMEOUT);

    booking.priceDecided(ride.rideId(), false);

    awaitState(jobId, "CANCELLED");
    assertThat(broker.cancelledRides()).containsExactly(ride.rideId());
    assertThat(broker.approvedRides()).isEmpty();
    assertThat(payments.reservations()).isEmpty();
  }

  @Test
  void rideIsCancelledIfNoDriverAccepts() throws Exception {
    TaxiBooking booking = start(Duration.ofSeconds(1));
    UUID jobId = booking.requestRide(ride);
    broker.nextRequest(ANSWER_TIMEOUT);

    assertThat(notifications.next(ANSWER_TIMEOUT).message())
        .isEqualTo("Sorry, no taxi is available right now.");
    awaitState(jobId, "CANCELLED");
    assertThat(broker.cancelledRides()).containsExactly(ride.rideId());
    assertThat(payments.reservations()).isEmpty();
  }

  @Test
  void rideCancelledByTheCustomerIsWithdrawnAtTheBroker() throws Exception {
    TaxiBooking booking = start(Duration.ofMinutes(1));
    UUID jobId = booking.requestRide(ride);
    broker.nextRequest(ANSWER_TIMEOUT);
    awaitState(jobId, "WAITING");

    assertThat(booking.cancelRide(jobId)).isTrue();

    awaitState(jobId, "CANCELLED");
    assertThat(broker.cancelledRides()).containsExactly(ride.rideId());
    assertThat(booking.cancelRide(jobId)).as("already cancelled").isFalse();
  }

  @Test
  void failedApprovalReleasesThePaymentAndWithdrawsTheRide() throws Exception {
    broker.failApprovals(true);
    TaxiBooking booking = start(Duration.ofMinutes(1));
    UUID jobId = booking.requestRide(ride);
    broker.nextRequest(ANSWER_TIMEOUT);
    booking.driverAccepted(ride.rideId(), offer);
    notifications.next(ANSWER_TIMEOUT);

    booking.priceDecided(ride.rideId(), true);

    awaitState(jobId, "FAILED");
    assertThat(payments.releasedReservations()).containsExactly("reservation-" + ride.rideId());
    assertThat(broker.cancelledRides()).containsExactly(ride.rideId());
    assertThat(broker.approvedRides()).isEmpty();
  }

  private TaxiBooking start(Duration driverTimeout) {
    bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("taxi-" + UUID.randomUUID())
            .jsonMapper(new JacksonJsonMapper())
            .autoStartPolling(false)
            .build();
    TaxiBooking booking =
        TaxiBooking.register(
            bikesched, broker, payments, notifications, driverTimeout, Duration.ofMinutes(1));
    bikesched.startPolling();
    return booking;
  }

  private static void awaitState(UUID jobId, String state) {
    await()
        .atMost(ANSWER_TIMEOUT)
        .untilAsserted(() -> assertThat(jobState(jobId)).isEqualTo(state));
  }

  private static String jobState(UUID jobId) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var query = connection.prepareStatement("select state from bikesched_jobs where id = ?")) {
      query.setObject(1, jobId);
      try (var rs = query.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}
