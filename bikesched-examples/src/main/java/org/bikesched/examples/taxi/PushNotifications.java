package org.bikesched.examples.taxi;

/** Sends push notifications to the customers' phones. */
public interface PushNotifications {

  /**
   * Sends a push notification.
   *
   * @param customerId the customer
   * @param message the message
   */
  void send(String customerId, String message);
}
