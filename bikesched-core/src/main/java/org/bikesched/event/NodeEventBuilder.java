// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import java.sql.Connection;
import java.util.UUID;

/**
 * An event builder published by a bikesched node.
 *
 * @param <P> the payload type
 */
public interface NodeEventBuilder<P> extends EventBuilder<P> {
  /**
   * Sets the connection used to publish the event in the caller's transaction. bikesched does not
   * manage this connection.
   *
   * @param connection caller-owned connection; {@code null} uses bikesched's own transaction
   * @return this builder
   */
  NodeEventBuilder<P> connection(Connection connection);

  @Override
  NodeEventBuilder<P> payload(P payload);

  @Override
  NodeEventBuilder<P> retention(java.time.Duration retention);

  /**
   * Publishes the event.
   *
   * @return the id of the published event; with a {@link #connection(Connection) connection}, the
   *     event exists once the caller's transaction commits
   * @throws IllegalArgumentException if the caller's connection is in auto-commit mode
   */
  @Override
  UUID publish();
}
