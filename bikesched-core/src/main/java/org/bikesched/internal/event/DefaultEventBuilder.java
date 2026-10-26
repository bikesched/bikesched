// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.event;

import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.event.EventBuilder;
import org.bikesched.event.NodeEventBuilder;
import org.bikesched.history.Actor;
import org.bikesched.internal.util.ValidationUtil;
import org.jspecify.annotations.Nullable;

/** The {@link EventBuilder} implementation. */
public final class DefaultEventBuilder<P extends @Nullable Object> implements NodeEventBuilder<P> {

  /** Publishes an event built by an {@link EventBuilder}; implemented by the owning bikesched. */
  @FunctionalInterface
  public interface Publisher {
    /**
     * {@code retention} is the event's retention, {@code null} for the node default; {@code
     * idempotencyKey} and {@code reference} are {@code null} for none; {@code actor} is the user
     * publishing it, {@code null} for the application; {@code connection} is the caller's
     * connection, or {@code null} to use bikesched's own.
     */
    UUID publish(
        String eventName,
        String eventKey,
        @Nullable String payload,
        @Nullable Duration retention,
        @Nullable String idempotencyKey,
        @Nullable String reference,
        @Nullable Actor actor,
        @Nullable Connection connection);
  }

  private final String eventName;
  private final String eventKey;
  private final JsonMapper jsonMapper;
  private final Publisher publisher;
  private @Nullable String payload;
  private @Nullable Duration retention; // null: the node default
  private @Nullable String idempotencyKey;
  private @Nullable String reference;
  private @Nullable Actor actor;
  private @Nullable Connection connection;

  public DefaultEventBuilder(
      String eventName, String eventKey, JsonMapper jsonMapper, Publisher publisher) {
    this.eventName = eventName;
    this.eventKey = eventKey;
    this.jsonMapper = jsonMapper;
    this.publisher = publisher;
  }

  @Override
  public NodeEventBuilder<P> payload(@Nullable P payload) {
    this.payload = payload == null ? null : jsonMapper.toJson(payload);
    return this;
  }

  @Override
  public NodeEventBuilder<P> payloadJson(@Nullable String json) {
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
  public NodeEventBuilder<P> reference(@Nullable String reference) {
    this.reference = reference == null ? null : ValidationUtil.requireName(reference, "reference");
    return this;
  }

  @Override
  public NodeEventBuilder<P> idempotencyKey(String idempotencyKey) {
    this.idempotencyKey = ValidationUtil.requireKey(idempotencyKey, "idempotency key");
    return this;
  }

  @Override
  public NodeEventBuilder<P> actor(Actor actor) {
    this.actor = Objects.requireNonNull(actor, "actor");
    return this;
  }

  @Override
  public NodeEventBuilder<P> connection(@Nullable Connection connection) {
    this.connection = connection;
    return this;
  }

  @Override
  public UUID publish() {
    return publisher.publish(
        eventName, eventKey, payload, retention, idempotencyKey, reference, actor, connection);
  }
}
