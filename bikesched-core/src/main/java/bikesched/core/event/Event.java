package bikesched.core.event;

import bikesched.core.JsonMapper;
import bikesched.core.job.Job;

/**
 * An event received by a job waiting for it, passed to an {@link EventHandler}, see {@link
 * Job#waitForEvent(String, String, String)}.
 */
public interface Event {

  /**
   * Returns the event name.
   *
   * @return the event name, e.g. {@code payment-received}
   */
  String name();

  /**
   * Returns the event key.
   *
   * @return the key identifying the event instance, e.g. an order id
   */
  String key();

  /**
   * Returns the raw event payload.
   *
   * @return the payload as JSON text, may be {@code null}
   * @see #payload(Class)
   */
  String payload();

  /**
   * Returns the event payload converted by the configured {@link JsonMapper}:
   *
   * <pre>{@code
   * Payment payment = event.payload(Payment.class);
   * }</pre>
   *
   * @param type the type to convert the payload to
   * @param <T> the type to convert the payload to
   * @return the payload, or {@code null} if the event has none
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default {@link JsonMapper#rawJson()} for types other than {@link String}
   */
  <T> T payload(Class<T> type);
}
