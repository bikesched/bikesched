// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.taxi;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.event.TypedEvent;
import org.bikesched.job.Job;
import org.bikesched.job.TypedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * node.
 *
 * <p>Steps with side effects declare a compensation: if the ride is cancelled (no driver or no
 * answer in time, the price declined, or the customer cancelling via {@link #cancelRide}) or fails
 * permanently (e.g. the broker cannot approve it), bikesched undoes the completed steps in reverse
 * order: it releases the payment and withdraws the ride at the broker.
 */
public final class TaxiBooking {

  private static final Logger LOG = LoggerFactory.getLogger(TaxiBooking.class);

  /** The job name of rides, with the ride request as payload. */
  public static final TypedJob<RideRequest> JOB_NAME = TypedJob.of("taxi-ride", RideRequest.class);

  /** Event published when a driver accepted a ride, keyed by the ride id. */
  public static final TypedEvent<DriverOffer> DRIVER_ACCEPTED =
      TypedEvent.of("taxi-driver-accepted", DriverOffer.class);

  /** Event published when the customer accepted or declined the price, keyed by the ride id. */
  public static final TypedEvent<PriceDecision> PRICE_DECIDED =
      TypedEvent.of("taxi-price-decided", PriceDecision.class);

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
    bikesched.registerJobHandler(JOB_NAME, booking::book);
    return booking;
  }

  /**
   * A customer requests a ride.
   *
   * @param ride the ride request
   * @return the id of the ride's job
   */
  public UUID requestRide(RideRequest ride) {
    return bikesched.newJob(JOB_NAME).payload(ride).maxAttempts(3).schedule();
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
   * The customer cancels the ride, e.g. in the app, while it is being booked: what was done so far
   * is compensated.
   *
   * @param jobId the id of the ride's job
   * @return {@code false} if the booking has ended already
   */
  public boolean cancelRide(UUID jobId) {
    return bikesched.cancel(jobId, "Cancelled by the customer");
  }

  /**
   * The ride's job. It runs again from the start after every wait and retry, and to compensate it:
   * completed steps and received events are skipped, returning their stored results.
   *
   * <p>Each step logs its data when it executes; a compensation logs exactly the same data (same
   * formatting method), so both can be matched in the log. The log pattern adds the job id and step
   * name, which bikesched puts into the logging context while a step or compensation runs.
   */
  private void book(Job job, RideRequest ride) throws Exception {
    String customer = ride.customerId();

    // 1. Hand the request to the broker, which forwards it to its drivers.
    job.newStep("send-to-broker", Void.class)
        .compensateWith(
            (ignored, ignored2) -> {
              LOG.info("Compensating: withdrawing {}", describe(ride));
              broker.cancelRide(ride.rideId());
            })
        .run(
            ignored -> {
              LOG.info("Executing: requesting {}", describe(ride));
              broker.requestRide(ride);
              return null;
            });

    // 2. Wait until a driver accepts the ride with a price.
    DriverOffer offer =
        job.waitForEvent("await-driver", DRIVER_ACCEPTED, ride.rideId())
            .timeout(driverTimeout)
            .onFailure(failing -> failing.resolve(null)) // no driver in time
            .receive();
    if (offer == null) {
      job.step(
          "notify-no-driver",
          Void.class,
          ignored -> {
            LOG.info("Executing: no driver accepted {}", describe(ride));
            notifications.send(customer, "Sorry, no taxi is available right now.");
            return null;
          });
      job.cancel("No driver accepted the ride within " + driverTimeout); // withdraws the request
      return;
    }

    // 3. The customer has to accept the driver's price.
    job.step(
        "ask-for-price",
        Void.class,
        ignored -> {
          LOG.info("Executing: asking {} to accept {}", customer, describe(offer));
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
    PriceDecision decision =
        job.waitForEvent("await-price-decision", PRICE_DECIDED, ride.rideId())
            .timeout(priceTimeout)
            .onFailure(failing -> failing.resolve(new PriceDecision(false))) // no answer: declined
            .receive();
    if (!decision.accepted()) {
      job.cancel("The customer did not accept the price"); // withdraws the request
      return;
    }

    // 4. Reserve the payment (charged after the ride); released if the booking does not complete.
    //    The compensation receives the stored reservation, so it logs the same payment data.
    PaymentReservation reservation =
        job.newStep("reserve-payment", PaymentReservation.class)
            .compensateWith(
                (ignored, stored) -> {
                  LOG.info("Compensating: releasing {}", describe(stored));
                  payments.release(stored);
                })
            .run(
                ignored -> {
                  PaymentReservation reserved =
                      payments.reserve(customer, offer.price(), offer.currency(), ride.rideId());
                  LOG.info("Executing: reserved {}", describe(reserved));
                  return reserved;
                });

    // 5. Approve the ride at the broker: the driver is on the way.
    RideApproval approval =
        job.step(
            "approve-ride",
            RideApproval.class,
            ignored -> {
              LOG.info("Executing: approving {} with {}", describe(ride), describe(reservation));
              return broker.approveRide(ride.rideId(), offer, reservation);
            });

    // 6. Tell the customer when the taxi arrives.
    job.step(
        "notify-eta",
        Void.class,
        ignored -> {
          LOG.info(
              "Executing: notifying {} of {}, arriving in {} minutes",
              customer,
              approval.driverName(),
              approval.etaMinutes());
          notifications.send(
              customer,
              approval.driverName()
                  + " is on the way and arrives in about "
                  + approval.etaMinutes()
                  + " minutes.");
          return null;
        });
  }

  private static String describe(RideRequest ride) {
    return "ride "
        + ride.rideId()
        + " of "
        + ride.customerId()
        + " ("
        + ride.pickup()
        + " -> "
        + ride.destination()
        + ")";
  }

  private static String describe(DriverOffer offer) {
    return "offer of driver " + offer.driverId() + " for " + offer.price() + " " + offer.currency();
  }

  private static String describe(PaymentReservation reservation) {
    return "payment "
        + reservation.reservationId()
        + " ("
        + reservation.amount()
        + " "
        + reservation.currency()
        + ")";
  }
}
