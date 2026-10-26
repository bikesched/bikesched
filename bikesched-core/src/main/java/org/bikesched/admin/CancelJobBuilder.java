// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.UUID;
import org.bikesched.history.Actor;
import org.jspecify.annotations.Nullable;

/**
 * Cancels a job, obtained via {@link Admin#cancelJob(UUID)}: like {@link
 * org.bikesched.Bikesched#cancel(UUID, String)}, optionally naming the user on the {@code
 * CANCEL_REQUESTED} history entry.
 *
 * <pre>{@code
 * bikesched.admin().cancelJob(jobId).message("customer called").actor(Actor.user("simon")).cancel();
 * }</pre>
 */
public interface CancelJobBuilder {

  /**
   * Sets why the job is cancelled, stored as the job's {@code last_error} (optional).
   *
   * @param message the message, {@code null} for none
   * @return this builder
   */
  CancelJobBuilder message(@Nullable String message);

  /**
   * Names the user cancelling the job on its {@code CANCEL_REQUESTED} history entry (optional;
   * without it, the entry names the application). The cancellation itself is recorded by the node
   * that performs it.
   *
   * @param actor the user
   * @return this builder
   */
  CancelJobBuilder actor(Actor actor);

  /**
   * Requests the cancellation, see {@link org.bikesched.Bikesched#cancel(UUID)}.
   *
   * @return {@code false} if the job does not exist, has ended or is being compensated
   * @throws org.bikesched.BikeschedException if the database cannot be accessed
   */
  boolean cancel();
}
