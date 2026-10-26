// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.UUID;
import org.bikesched.history.Actor;

/**
 * Retries a failed job, obtained via {@link Admin#retryJob(UUID)}: like {@link
 * org.bikesched.Bikesched#retry(UUID)}, optionally naming the user on the {@code RETRIED} history
 * entry.
 *
 * <pre>{@code
 * bikesched.admin().retryJob(jobId).actor(Actor.user("simon")).retry();
 * }</pre>
 */
public interface RetryJobBuilder {

  /**
   * Names the user retrying the job on its {@code RETRIED} history entry (optional; without it, the
   * entry names the application).
   *
   * @param actor the user
   * @return this builder
   */
  RetryJobBuilder actor(Actor actor);

  /**
   * Schedules the failed job to run again, see {@link org.bikesched.Bikesched#retry(UUID)}.
   *
   * @return {@code false} if the job does not exist or has not failed
   * @throws IllegalArgumentException if the job is an occurrence of a recurring job
   * @throws org.bikesched.BikeschedException if the database cannot be accessed
   */
  boolean retry();
}
