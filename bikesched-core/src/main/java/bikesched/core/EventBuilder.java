package bikesched.core;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * Builds and publishes an event, obtained via {@link Bikesched#newEvent(String, String)}:
 *
 * <pre>{@code
 * List<Step> received = bikesched.newEvent("payment-received", orderId)
 *         .payload(payment)
 *         .publish();
 * }</pre>
 *
 * <p>Publishing stores the event and hands it to every step currently waiting for it (same name and
 * key, see {@link Job#waitForEvent(String, String, String)}); their jobs continue right away. The
 * event is kept for the {@link BikeschedBuilder#eventRetention(java.time.Duration) retention
 * period}, so a step that starts waiting for it later still receives it. Not thread-safe.
 */
public final class EventBuilder {

  /** Publishes an event built by an {@link EventBuilder}; implemented by the owning bikesched. */
  @FunctionalInterface
  interface Publisher {
    /** {@code connection} is the caller's connection, or {@code null} to use bikesched's own. */
    List<Step> publish(String eventName, String eventKey, String payload, Connection connection);
  }

  private final String eventName;
  private final String eventKey;
  private final JsonMapper jsonMapper;
  private final Publisher publisher;
  private String payload;
  private Connection connection;

  EventBuilder(String eventName, String eventKey, JsonMapper jsonMapper, Publisher publisher) {
    this.eventName = Objects.requireNonNull(eventName, "eventName");
    this.eventKey = Objects.requireNonNull(eventKey, "eventKey");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  /**
   * Sets the event payload (optional, default none), converted to JSON right away by the configured
   * {@link JsonMapper}; waiting steps receive it as their result.
   *
   * @param payload the payload, may be {@code null}
   * @return this builder
   * @throws RuntimeException any exception of the mapper, e.g. {@link IllegalArgumentException}
   *     from the default {@link JsonStringMapper} for types other than {@link String}
   */
  public EventBuilder payload(Object payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return this;
  }

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
  public EventBuilder connection(Connection connection) {
    this.connection = connection;
    return this;
  }

  /**
   * Publishes the event.
   *
   * @return the steps that received the event, with their job ids; empty if no step was waiting for
   *     it (yet)
   * @throws IllegalArgumentException if the caller's connection is in auto-commit mode
   * @throws BikeschedException if the event cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public List<Step> publish() {
    return publisher.publish(eventName, eventKey, payload, connection);
  }
}
