package bikesched.examples.taxi;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** An in-memory taxi broker: the example's drivers take ride requests from {@link #nextRequest}. */
public final class SimulatedTaxiBroker implements TaxiBroker {

  private final BlockingQueue<RideRequest> requests = new LinkedBlockingQueue<>();
  private final List<String> approvedRides = new CopyOnWriteArrayList<>();
  private final Set<String> cancelledRides = ConcurrentHashMap.newKeySet();

  /** Creates the broker. */
  public SimulatedTaxiBroker() {}

  @Override
  public void requestRide(RideRequest ride) {
    System.out.println("[broker] forwarding ride " + ride.rideId() + " to the drivers");
    requests.add(ride);
  }

  @Override
  public RideApproval approveRide(
      String rideId, DriverOffer offer, PaymentReservation reservation) {
    System.out.println(
        "[broker] ride "
            + rideId
            + " approved for "
            + offer.driverName()
            + " (payment "
            + reservation.reservationId()
            + ")");
    approvedRides.add(rideId);
    return new RideApproval(offer.driverName(), 7);
  }

  @Override
  public void cancelRide(String rideId) {
    System.out.println("[broker] ride " + rideId + " cancelled");
    cancelledRides.add(rideId);
  }

  /**
   * Waits for the next ride request forwarded to the drivers.
   *
   * @param timeout how long to wait at most
   * @return the ride request
   * @throws InterruptedException if interrupted while waiting
   * @throws IllegalStateException if no request arrived in time
   */
  public RideRequest nextRequest(Duration timeout) throws InterruptedException {
    RideRequest ride = requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (ride == null) {
      throw new IllegalStateException("No ride request within " + timeout);
    }
    return ride;
  }

  /**
   * Returns the approved rides.
   *
   * @return the ids of the approved rides, in order
   */
  public List<String> approvedRides() {
    return List.copyOf(approvedRides);
  }

  /**
   * Returns the cancelled rides.
   *
   * @return the ids of the cancelled rides
   */
  public Set<String> cancelledRides() {
    return Set.copyOf(cancelledRides);
  }
}
