// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.history.Actor;
import org.jspecify.annotations.Nullable;

/**
 * An event builder published by a bikesched node.
 *
 * @param <P> the payload type
 */
public interface NodeEventBuilder<P extends @Nullable Object> extends EventBuilder<P> {
  /**
   * Sets the connection used to publish the event in the caller's transaction. bikesched does not
   * manage this connection.
   *
   * @param connection caller-owned connection; {@code null} uses bikesched's own transaction
   * @return this builder
   */
  NodeEventBuilder<P> connection(@Nullable Connection connection);

  /**
   * Sets an idempotency key (optional), so that a caller that retries, e.g. a webhook delivered
   * twice, publishes the event once:
   *
   * <pre>{@code
   * bikesched.newEvent("payment-received", orderId)
   *     .payload(payment)
   *     .idempotencyKey(webhook.deliveryId())
   *     .publish();   // a retry with the same key returns the first event's id
   * }</pre>
   *
   * <p>While an event with the same name, key and idempotency key is kept (its {@link
   * #retention(Duration) retention} has not passed), {@link #publish()} returns that event's id
   * without publishing: no event is stored and no waiting step receives anything. The duplicate's
   * payload is ignored. With a retention of {@link Duration#ZERO}, the key has no effect.
   *
   * @param idempotencyKey the key, not blank, at most 500 characters
   * @return this builder
   * @throws IllegalArgumentException if the key is blank or too long
   */
  NodeEventBuilder<P> idempotencyKey(String idempotencyKey);

  /**
   * Names the user publishing the event on its {@code PUBLISHED} history entry (optional; without
   * it, the entry names the application). The steps receiving the event and the jobs it resumes are
   * recorded as the application's, caused by the event. Has no effect without the history.
   *
   * <pre>{@code
   * bikesched.newEvent("payment-received", orderId).actor(Actor.user("simon")).publish();
   * }</pre>
   *
   * @param actor the user
   * @return this builder
   * @see org.bikesched.BikeschedBuilder#history(Duration)
   */
  NodeEventBuilder<P> actor(Actor actor);

  @Override
  NodeEventBuilder<P> payload(@Nullable P payload);

  @Override
  NodeEventBuilder<P> payloadJson(@Nullable String json);

  @Override
  NodeEventBuilder<P> retention(Duration retention);

  @Override
  NodeEventBuilder<P> reference(@Nullable String reference);

  /**
   * Publishes the event.
   *
   * @return the id of the published event, or of the earlier event with the same {@link
   *     #idempotencyKey(String) idempotency key}; with a {@link #connection(Connection)
   *     connection}, the event exists once the caller's transaction commits
   * @throws IllegalArgumentException if the caller's connection is in auto-commit mode
   */
  @Override
  UUID publish();
}
