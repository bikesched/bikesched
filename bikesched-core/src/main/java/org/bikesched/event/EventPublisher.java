// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import org.jspecify.annotations.Nullable;

/** Shared event publication API for a bikesched node and a running job. */
public interface EventPublisher {
  /**
   * Starts building an event with an untyped payload.
   *
   * @param eventName event name
   * @param eventKey event key
   * @return the event builder
   */
  EventBuilder<Object> newEvent(String eventName, String eventKey);

  /**
   * Starts building an event with a typed payload.
   *
   * @param typedEvent event name and payload type
   * @param eventKey event key
   * @param <T> payload type
   * @return the event builder
   */
  <T extends @Nullable Object> EventBuilder<T> newEvent(TypedEvent<T> typedEvent, String eventKey);
}
