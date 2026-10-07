// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

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
   * @param eventType event name and payload type
   * @param eventKey event key
   * @param <T> payload type
   * @return the event builder
   */
  <T> EventBuilder<T> newEvent(EventType<T> eventType, String eventKey);
}
