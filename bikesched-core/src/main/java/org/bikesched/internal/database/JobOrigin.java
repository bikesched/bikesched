// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Instant;
import java.util.UUID;

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
   */
  record Child(UUID parentJobId) implements JobOrigin {}

  /**
   * An occurrence of a recurring job.
   *
   * @param recurrence the description of the recurring job's recurrence
   * @param fixedRateAt the planned run if the recurrence is fixed-rate, {@code null} otherwise
   */
  record Occurrence(String recurrence, Instant fixedRateAt) implements JobOrigin {}

  /** The origin of a stored job, from its nullable columns. */
  static JobOrigin of(UUID parentJobId, String recurrence, Instant fixedRateAt) {
    if (parentJobId != null) {
      return new Child(parentJobId);
    }
    return recurrence == null ? new OneOff() : new Occurrence(recurrence, fixedRateAt);
  }
}
