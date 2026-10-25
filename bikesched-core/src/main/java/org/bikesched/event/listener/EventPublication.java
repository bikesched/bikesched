// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event.listener;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A published event, reported to an {@link EventListener}. Payload and key are left out: they can
 * be sensitive or high-cardinality; look the event up by its id if needed. The reference is
 * included for listeners that log or forward publications; like the key, it is one value per
 * business entity, so not suited as a metric tag.
 *
 * @param eventId the id of the stored event, as returned by {@code publish()}
 * @param eventName the event's name
 * @param reference the event's reference (see {@link
 *     org.bikesched.event.EventBuilder#reference(String)}), {@code null} if it has none
 * @param source where the event was published
 * @param receivedSteps the number of waiting steps that received the event when it was published
 */
public record EventPublication(
    UUID eventId,
    String eventName,
    @Nullable String reference,
    EventSource source,
    int receivedSteps) {

  /** Checks the components. */
  public EventPublication {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventName, "eventName");
    Objects.requireNonNull(source, "source");
    if (receivedSteps < 0) {
      throw new IllegalArgumentException("receivedSteps must not be negative: " + receivedSteps);
    }
  }
}
