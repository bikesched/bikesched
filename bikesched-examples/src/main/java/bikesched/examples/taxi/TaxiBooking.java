package bikesched.examples.taxi;

import bikesched.core.Bikesched;
import bikesched.core.job.Job;
import java.time.Duration;
import java.util.UUID;

/**
 * Books a taxi ride as one durable bikesched job:
 *
 * <ol>
 *   <li>the customer requests a ride ({@link #requestRide}), which schedules the ride's job;
 *   <li>the job sends the request to the {@link TaxiBroker} and waits until a driver accepts it
 *       with a price ({@link #driverAccepted}, e.g. called by the broker's webhook);
 *   <li>it asks the customer to accept the price and waits for the answer ({@link #priceDecided},
 *       e.g. called by the customer's app);
 *   <li>it reserves the payment;
 *   <li>it approves the ride at the broker;
 *   <li>it sends the customer a push notification with the estimated time of arrival.
 * </ol>
 *
 * <p>Each step runs once: if a step fails (e.g. the payment provider is down), the job is retried
 * and continues after the last completed step. While waiting for the driver or the customer, the
 * job holds no thread and no database connection; it survives restarts and may continue on another
 * node. Without a driver or an answer in time, or if the customer declines, the ride is cancelled.
 */
public final class TaxiBooking {

  /** The job type of rides. */
  public static final String JOB_TYPE = "taxi-ride";

  /** Event published when a driver accepted a ride, keyed by the ride id. */
  public static final String DRIVER_ACCEPTED = "taxi-driver-accepted";

  /** Event published when the customer accepted or declined the price, keyed by the ride id. */
  public static final String PRICE_DECIDED = "taxi-price-decided";

  private final Bikesched bikesched;
  private final TaxiBroker broker;
  private final PaymentService payments;
  private final PushNotifications notifications;
  private final Duration driverTimeout;
  private final Duration priceTimeout;

  private TaxiBooking(
      Bikesched bikesched,
      TaxiBroker broker,
      PaymentService payments,
      PushNotifications notifications,
      Duration driverTimeout,
      Duration priceTimeout) {
    this.bikesched = bikesched;
    this.broker = broker;
    this.payments = payments;
    this.notifications = notifications;
    this.driverTimeout = driverTimeout;
    this.priceTimeout = priceTimeout;
  }

  /**
   * Registers the ride handler on the bikesched instance.
   *
   * @param bikesched the bikesched instance, configured with a JSON mapper for records
   * @param broker the taxi broker
   * @param payments the payment provider
   * @param notifications the push notification service
   * @param driverTimeout how long to wait for a driver to accept a ride
   * @param priceTimeout how long to wait for the customer to accept the price
   * @return the booking service
   */
  public static TaxiBooking register(
      Bikesched bikesched,
      TaxiBroker broker,
      PaymentService payments,
      PushNotifications notifications,
      Duration driverTimeout,
      Duration priceTimeout) {
    var booking =
        new TaxiBooking(bikesched, broker, payments, notifications, driverTimeout, priceTimeout);
    bikesched.registerHandler(JOB_TYPE, booking::book);
    return booking;
  }

  /**
   * A customer requests a ride.
   *
   * @param ride the ride request
   * @return the id of the ride's job
   */
  public UUID requestRide(RideRequest ride) {
    return bikesched.newJob(JOB_TYPE).payload(ride).maxAttempts(5).schedule();
  }

  /**
   * A driver accepted the ride with a price, e.g. reported by the broker's webhook.
   *
   * @param rideId the ride
   * @param offer the driver and the price
   */
  public void driverAccepted(String rideId, DriverOffer offer) {
    bikesched.newEvent(DRIVER_ACCEPTED, rideId).payload(offer).publish();
  }

  /**
   * The customer accepted or declined the price, e.g. in the app.
   *
   * @param rideId the ride
   * @param accepted whether the customer accepted the price
   */
  public void priceDecided(String rideId, boolean accepted) {
    bikesched.newEvent(PRICE_DECIDED, rideId).payload(new PriceDecision(accepted)).publish();
  }

  /**
   * The ride's job. It runs again from the start after every wait and retry: completed steps and
   * received events are skipped, returning their stored results.
   */
  private void book(Job job) throws Exception {
    RideRequest ride = job.payload(RideRequest.class);
    String customer = ride.customerId();

    // 1. Hand the request to the broker, which forwards it to its drivers.
    job.step(
        "send-to-broker",
        Void.class,
        _ -> {
          broker.requestRide(ride);
          return null;
        });

    // 2. Wait until a driver accepts the ride with a price.
    DriverOffer offer =
        job.waitForEvent("await-driver", DRIVER_ACCEPTED, ride.rideId())
            .<DriverOffer>timeout(driverTimeout, () -> null)
            .receive(event -> event.payload(DriverOffer.class));
    if (offer == null) {
      job.step(
          "notify-no-driver",
          Void.class,
          _ -> {
            broker.cancelRide(ride.rideId());
            notifications.send(customer, "Sorry, no taxi is available right now.");
            return null;
          });
      job.cancel("No driver accepted the ride within " + driverTimeout);
      return;
    }

    // 3. The customer has to accept the driver's price.
    job.step(
        "ask-for-price",
        Void.class,
        _ -> {
          notifications.send(
              customer,
              offer.driverName()
                  + " can take you to "
                  + ride.destination()
                  + " for "
                  + offer.price()
                  + " "
                  + offer.currency()
                  + ". Accept?");
          return null;
        });
    boolean accepted =
        job.waitForEvent("await-price-decision", PRICE_DECIDED, ride.rideId())
            .timeout(priceTimeout, () -> false)
            .receive(event -> event.payload(PriceDecision.class).accepted());
    if (!accepted) {
      job.step(
          "cancel-at-broker",
          Void.class,
          _ -> {
            broker.cancelRide(ride.rideId());
            return null;
          });
      job.cancel("The customer did not accept the price");
      return;
    }

    // 4. Reserve the payment (charged after the ride).
    PaymentReservation reservation =
        job.step(
            "reserve-payment",
            PaymentReservation.class,
            _ -> payments.reserve(customer, offer.price(), offer.currency(), ride.rideId()));

    // 5. Approve the ride at the broker: the driver is on the way.
    RideApproval approval =
        job.step(
            "approve-ride",
            RideApproval.class,
            _ -> broker.approveRide(ride.rideId(), offer, reservation));

    // 6. Tell the customer when the taxi arrives.
    job.step(
        "notify-eta",
        Void.class,
        _ -> {
          notifications.send(
              customer,
              approval.driverName()
                  + " is on the way and arrives in about "
                  + approval.etaMinutes()
                  + " minutes.");
          return null;
        });
  }
}
