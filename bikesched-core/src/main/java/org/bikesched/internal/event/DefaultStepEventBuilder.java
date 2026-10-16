// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.event;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.bikesched.event.StepEventBuilder;
import org.jspecify.annotations.Nullable;

/** Builder for an event published in a durable job step. */
public final class DefaultStepEventBuilder<P extends @Nullable Object>
    implements StepEventBuilder<P> {
  @FunctionalInterface
  public interface Publisher {
    UUID publish(
        String name,
        String key,
        @Nullable String payload,
        @Nullable Duration retention,
        @Nullable String stepName);
  }

  private final String eventName;
  private final String eventKey;
  private final JsonMapper mapper;
  private final Publisher publisher;
  private @Nullable String payload;
  private @Nullable Duration retention;
  private @Nullable String stepName;

  public DefaultStepEventBuilder(
      String eventName, String eventKey, JsonMapper mapper, Publisher publisher) {
    this.eventName = eventName;
    this.eventKey = eventKey;
    this.mapper = mapper;
    this.publisher = publisher;
  }

  @Override
  public StepEventBuilder<P> payload(@Nullable P value) {
    payload = value == null ? null : mapper.toJson(value);
    return this;
  }

  @Override
  public StepEventBuilder<P> payloadJson(@Nullable String json) {
    payload = json;
    return this;
  }

  @Override
  public StepEventBuilder<P> retention(Duration value) {
    Objects.requireNonNull(value, "retention");
    if (value.isNegative())
      throw new IllegalArgumentException("retention must not be negative: " + value);
    retention = value;
    return this;
  }

  @Override
  public StepEventBuilder<P> stepName(String value) {
    stepName = Objects.requireNonNull(value, "stepName");
    return this;
  }

  @Override
  public UUID publish() {
    return publisher.publish(eventName, eventKey, payload, retention, stepName);
  }
}
