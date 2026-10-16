// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.UUID;

/**
 * A claimed job whose attempt ends: who holds it and what follows from its end.
 *
 * @param jobId the job id
 * @param lock the lock of the claim (node and token)
 * @param failureHandler whether the job type has a failure handler on this node: a job failing
 *     permanently is {@code FAILING} then
 * @param followUp what follows once the job ended
 */
public record ClaimEnd(UUID jobId, JobLock lock, boolean failureHandler, FollowUp followUp) {

  public ClaimEnd {}

  /** A claim ending without failure handler or follow-up, e.g. in store tests. */
  public static ClaimEnd of(UUID jobId, JobLock lock) {
    return new ClaimEnd(jobId, lock, false, new FollowUp.None());
  }

  /** What follows in the same transaction once a job ended in a terminal state. */
  public sealed interface FollowUp {

    /** Nothing: a one-off job, or an occurrence whose next run is not known on this node. */
    record None() implements FollowUp {}

    /**
     * The parent's step waiting for this child receives the outcome.
     *
     * @param parentJobId the parent
     */
    record NotifyParent(UUID parentJobId) implements FollowUp {}

    /**
     * The next occurrence of the recurring job is inserted, unless one is pending.
     *
     * @param next the next occurrence
     */
    record ScheduleNext(NewJob next) implements FollowUp {}
  }
}
