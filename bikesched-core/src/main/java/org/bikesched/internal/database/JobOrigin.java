// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where a job comes from. Exclusive: a child job is never an occurrence (a step cannot start a
 * recurring job), and an occurrence never starts child jobs.
 */
public sealed interface JobOrigin {

  /** Scheduled on its own. */
  record OneOff() implements JobOrigin {}

  /**
   * Started as a step of its parent, whose step receives its outcome.
   *
   * @param parentJobId the parent
   * @param batchStepId the parent's batch step if it is an item of a batch, otherwise {@code null}
   * @param batchKey its key in the batch, {@code null} unless it is an item of a batch
   */
  record Child(UUID parentJobId, @Nullable UUID batchStepId, @Nullable String batchKey)
      implements JobOrigin {}

  /**
   * An occurrence of a recurring job.
   *
   * @param recurrence the description of the recurring job's recurrence
   * @param plannedRunAt the planned run, not moved by retries or event waits
   */
  record Occurrence(String recurrence, Instant plannedRunAt) implements JobOrigin {}

  /**
   * The origin of a stored job, from its nullable columns; an occurrence always has a planned run
   * ({@code bikesched_jobs_planned_run_at_check}).
   */
  static JobOrigin of(
      UUID parentJobId,
      @Nullable UUID batchStepId,
      @Nullable String batchKey,
      String recurrence,
      Instant plannedRunAt) {
    if (parentJobId != null) {
      return new Child(parentJobId, batchStepId, batchKey);
    }
    return recurrence == null ? new OneOff() : new Occurrence(recurrence, plannedRunAt);
  }
}
