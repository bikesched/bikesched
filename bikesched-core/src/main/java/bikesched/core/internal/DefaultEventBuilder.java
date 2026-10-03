package bikesched.core.internal;

import bikesched.core.JsonMapper;
import bikesched.core.event.EventBuilder;
import bikesched.core.event.ReceivedEvent;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/** The {@link EventBuilder} implementation. */
final class DefaultEventBuilder implements EventBuilder {

  /** Publishes an event built by an {@link EventBuilder}; implemented by the owning bikesched. */
  @FunctionalInterface
  interface Publisher {
    /** {@code connection} is the caller's connection, or {@code null} to use bikesched's own. */
    List<ReceivedEvent> publish(
        String eventName, String eventKey, String payload, Connection connection);
  }

  private final String eventName;
  private final String eventKey;
  private final JsonMapper jsonMapper;
  private final Publisher publisher;
  private String payload;
  private Connection connection;

  DefaultEventBuilder(
      String eventName, String eventKey, JsonMapper jsonMapper, Publisher publisher) {
    this.eventName = Objects.requireNonNull(eventName, "eventName");
    this.eventKey = Objects.requireNonNull(eventKey, "eventKey");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  public EventBuilder payload(Object payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return this;
  }

  @Override
  public EventBuilder connection(Connection connection) {
    this.connection = connection;
    return this;
  }

  @Override
  public List<ReceivedEvent> publish() {
    return publisher.publish(eventName, eventKey, payload, connection);
  }
}
