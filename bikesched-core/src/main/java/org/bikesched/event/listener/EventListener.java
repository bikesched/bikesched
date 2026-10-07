// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event.listener;

import org.bikesched.BikeschedBuilder;

/**
 * Observes the events published through a node, e.g. for metrics ({@code bikesched-micrometer});
 * set with {@link BikeschedBuilder#eventListener(EventListener)}. All methods do nothing by
 * default.
 *
 * <p>Methods are called synchronously on the publishing thread, so they must be fast and
 * thread-safe. Exceptions they throw are logged and otherwise ignored.
 */
public interface EventListener {

  /**
   * Called once an event was published and committed: by {@link
   * org.bikesched.event.NodeEventBuilder#publish()} before it returns, by a job's publishing step
   * the first time it publishes (not when a later run of the handler returns the stored id).
   *
   * <p>Not called for events published in the caller's transaction ({@link
   * org.bikesched.event.NodeEventBuilder#connection(java.sql.Connection)}): bikesched does not see
   * whether that transaction commits.
   *
   * @param publication the published event
   */
  default void onEventPublished(EventPublication publication) {}
}
