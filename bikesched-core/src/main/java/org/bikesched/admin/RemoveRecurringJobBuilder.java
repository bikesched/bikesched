// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import org.bikesched.history.Actor;

/**
 * Stops a recurring job, obtained via {@link Admin#removeRecurringJob(String)}: like {@link
 * org.bikesched.Bikesched#removeRecurringJob(String)}, optionally naming the user on the {@code
 * DELETED} history entry of the pending occurrence.
 *
 * <pre>{@code
 * bikesched.admin().removeRecurringJob("report").actor(Actor.user("simon")).remove();
 * }</pre>
 */
public interface RemoveRecurringJobBuilder {

  /**
   * Names the user removing the recurring job on the {@code DELETED} history entry of its pending
   * occurrence (optional; without it, the entry names the application).
   *
   * @param actor the user
   * @return this builder
   */
  RemoveRecurringJobBuilder actor(Actor actor);

  /**
   * Unregisters the recurring job on this node and deletes its pending occurrence, see {@link
   * org.bikesched.Bikesched#removeRecurringJob(String)}.
   *
   * @return whether a pending occurrence was deleted
   * @throws org.bikesched.BikeschedException if the database cannot be accessed
   */
  boolean remove();
}
