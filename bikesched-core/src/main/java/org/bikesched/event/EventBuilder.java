package org.bikesched.event;

import java.sql.Connection;
import java.util.List;
import org.bikesched.BikeschedBuilder;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;

/**
 * Builds and publishes an event, obtained via {@link org.bikesched.Bikesched#newEvent(String,
 * String)} or, with a typed payload, {@link org.bikesched.Bikesched#newEvent(EventType, String)}:
 *
 * <pre>{@code
 * List<ReceivedEvent> received = bikesched.newEvent("payment-received", orderId)
 *         .payload(payment)
 *         .publish();
 * }</pre>
 *
 * <p>Publishing stores the event and hands it to every step currently waiting for it (same name and
 * key, see {@link org.bikesched.job.Job#waitForEvent(String, String, String)}); their jobs continue
 * right away. The event is kept for the {@link BikeschedBuilder#eventRetention(java.time.Duration)
 * retention period}, so a step that starts waiting for it later still receives it. Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for an event name given as a string, the payload
 *     class of an {@link EventType}
 */
public interface EventBuilder<P> {

  /**
   * Sets the event payload (optional, default none), converted to JSON right away by the configured
   * {@link JsonMapper}; waiting steps receive it as their result.
   *
   * @param payload the payload, may be {@code null}
   * @return this builder
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default mapper (no {@code jsonMapper} configured) for types other than {@link
   *     String}
   */
  EventBuilder<P> payload(P payload);

  /**
   * Publishes the event as part of the caller's transaction (optional): the event is stored and the
   * waiting steps receive it only if the caller commits; other nodes see neither before. The
   * connection must not be in auto-commit mode. bikesched does not commit, roll back or close it.
   *
   * <p>Until the caller's transaction ends, it holds a lock on the event's name and key, so jobs
   * starting to wait for the same event meanwhile block until then; keep such transactions short.
   *
   * @param connection the caller's connection, or {@code null} to use bikesched's own
   * @return this builder
   */
  EventBuilder<P> connection(Connection connection);

  /**
   * Publishes the event.
   *
   * @return the steps that received the event, with their job ids; empty if no step was waiting for
   *     it (yet)
   * @throws IllegalArgumentException if the caller's connection is in auto-commit mode
   * @throws BikeschedException if the event cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  List<ReceivedEvent> publish();
}
