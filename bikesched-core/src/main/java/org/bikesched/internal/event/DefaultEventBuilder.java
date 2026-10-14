// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.event;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.event.EventBuilder;
import org.bikesched.event.NodeEventBuilder;
import org.bikesched.internal.util.ValidationUtil;

/** The {@link EventBuilder} implementation. */
public final class DefaultEventBuilder<P> implements NodeEventBuilder<P> {

  /** Publishes an event built by an {@link EventBuilder}; implemented by the owning bikesched. */
  @FunctionalInterface
  public interface Publisher {
    /**
     * {@code retention} is the event's retention, {@code null} for the node default; {@code
     * idempotencyKey} is {@code null} for none; {@code connection} is the caller's connection, or
     * {@code null} to use bikesched's own.
     */
    UUID publish(
        String eventName,
        String eventKey,
        String payload,
        Duration retention,
        String idempotencyKey,
        Connection connection);
  }

  private final String eventName;
  private final String eventKey;
  private final JsonMapper jsonMapper;
  private final Publisher publisher;
  private String payload;
  private Duration retention; // null: the node default
  private String idempotencyKey;
  private Connection connection;

  public DefaultEventBuilder(
      String eventName, String eventKey, JsonMapper jsonMapper, Publisher publisher) {
    this.eventName = Objects.requireNonNull(eventName, "eventName");
    this.eventKey = Objects.requireNonNull(eventKey, "eventKey");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  public NodeEventBuilder<P> payload(P payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return this;
  }

  @Override
  public NodeEventBuilder<P> payloadJson(String json) {
    this.payload = json;
    return this;
  }

  @Override
  public NodeEventBuilder<P> retention(Duration retention) {
    Objects.requireNonNull(retention, "retention");
    if (retention.isNegative()) {
      throw new IllegalArgumentException("retention must not be negative: " + retention);
    }
    this.retention = retention;
    return this;
  }

  @Override
  public NodeEventBuilder<P> idempotencyKey(String idempotencyKey) {
    this.idempotencyKey = ValidationUtil.requireKey(idempotencyKey, "idempotency key");
    return this;
  }

  @Override
  public NodeEventBuilder<P> connection(Connection connection) {
    this.connection = connection;
    return this;
  }

  @Override
  public UUID publish() {
    return publisher.publish(eventName, eventKey, payload, retention, idempotencyKey, connection);
  }
}
