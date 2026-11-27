// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A claimed job whose attempt ends: who holds it and what follows from its end.
 *
 * @param jobId the job id
 * @param lock the lock of the claim (node and token)
 * @param failureHandler whether the job has a failure handler on this node: a job failing
 *     permanently is {@code FAILING} then
 * @param followUp what follows once the job ended
 * @param batches whether the job may have batches still waiting (see {@link BatchStore}): they are
 *     locked first in the transaction ending it, and their held items cancelled once it ended
 */
public record ClaimEnd(
    UUID jobId, JobLock lock, boolean failureHandler, FollowUp followUp, boolean batches) {

  public ClaimEnd {}

  /** A claim ending without failure handler or follow-up, e.g. in store tests. */
  public static ClaimEnd of(UUID jobId, JobLock lock) {
    return new ClaimEnd(jobId, lock, false, new FollowUp.None(), false);
  }

  /** What follows in the same transaction once a job ended in a terminal state. */
  public sealed interface FollowUp {

    /** Nothing: a one-off job, or an occurrence whose next run is not known on this node. */
    record None() implements FollowUp {}

    /**
     * The parent's step waiting for this child receives the outcome.
     *
     * @param parentJobId the parent
     * @param batchStepId the parent's batch step if the child is an item of a batch, otherwise
     *     {@code null}
     */
    record NotifyParent(UUID parentJobId, @Nullable UUID batchStepId) implements FollowUp {}

    /**
     * The next occurrence of the recurring job is inserted, unless one is pending.
     *
     * @param next the next occurrence
     */
    record ScheduleNext(NewJob next) implements FollowUp {}
  }
}
