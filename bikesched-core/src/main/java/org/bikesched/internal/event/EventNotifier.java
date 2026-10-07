// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.event;

import java.util.Objects;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.database.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports committed publications to the node's {@link EventListener}; its exceptions must not
 * affect publishing, so they are logged and ignored.
 */
public final class EventNotifier {

  private static final Logger LOG = LoggerFactory.getLogger(EventNotifier.class);

  private final EventListener listener;

  /**
   * @param listener the node's listener
   */
  public EventNotifier(EventListener listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Reports a publication; call only after its transaction committed.
   *
   * @param eventName the event's name
   * @param source where it was published
   * @param publication the stored event
   */
  public void published(String eventName, EventSource source, EventStore.Publication publication) {
    try {
      listener.onEventPublished(
          new EventPublication(publication.eventId(), eventName, source, publication.received()));
    } catch (RuntimeException e) {
      LOG.warn("Event listener failed for event {}, ignoring it", publication.eventId(), e);
    }
  }
}
