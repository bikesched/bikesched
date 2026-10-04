package org.bikesched.examples.taxi;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** In-memory push notifications: the example's customer reads them from {@link #next}. */
public final class SimulatedPushNotifications implements PushNotifications {

  /**
   * A sent push notification.
   *
   * @param customerId the customer
   * @param message the message
   */
  public record Notification(String customerId, String message) {}

  private final BlockingQueue<Notification> sent = new LinkedBlockingQueue<>();

  /** Creates the push notification service. */
  public SimulatedPushNotifications() {}

  @Override
  public void send(String customerId, String message) {
    System.out.println("[push to " + customerId + "] " + message);
    sent.add(new Notification(customerId, message));
  }

  /**
   * Waits for the next push notification.
   *
   * @param timeout how long to wait at most
   * @return the notification
   * @throws InterruptedException if interrupted while waiting
   * @throws IllegalStateException if no notification was sent in time
   */
  public Notification next(Duration timeout) throws InterruptedException {
    Notification notification = sent.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (notification == null) {
      throw new IllegalStateException("No push notification within " + timeout);
    }
    return notification;
  }
}
