// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.event;

import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.database.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reports committed publications to an {@link EventListener}. */
public final class EventListenerUtil {

  private static final Logger LOG = LoggerFactory.getLogger(EventListenerUtil.class);

  private EventListenerUtil() {}

  /**
   * Reports a publication to the listener; call only after its transaction committed. Exceptions of
   * the listener must not affect publishing, so they are logged and ignored.
   *
   * @param listener the node's listener
   * @param eventName the event's name
   * @param source where it was published
   * @param publication the stored event
   */
  public static void notifyPublished(
      EventListener listener,
      String eventName,
      EventSource source,
      EventStore.Publication publication) {
    try {
      listener.onEventPublished(
          new EventPublication(publication.eventId(), eventName, source, publication.received()));
    } catch (RuntimeException e) {
      LOG.warn("Event listener failed for event {}, ignoring it", publication.eventId(), e);
    }
  }
}
