// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.List;
import java.util.Objects;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;

/**
 * Passes every publication to all {@link EventListener} beans of the context: the builder takes one
 * listener, so the starter composes the beans into one. Listeners are called in the order they were
 * resolved, i.e. their {@code @Order}.
 */
final class CompositeEventListener implements EventListener {

  private final List<EventListener> listeners;

  CompositeEventListener(List<EventListener> listeners) {
    this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
  }

  @Override
  public void onEventPublished(EventPublication publication) {
    listeners.forEach(listener -> listener.onEventPublished(publication));
  }
}
