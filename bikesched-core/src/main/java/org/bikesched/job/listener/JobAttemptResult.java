// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job.listener;

import java.time.Duration;
import java.util.Objects;

/**
 * The result of an attempt, reported to a {@link org.bikesched.job.listener.JobListener}.
 *
 * @param claim the attempt's claim
 * @param outcome what was recorded
 * @param duration from the start of processing until the outcome was recorded
 * @param isAbandoned whether the hard timeout abandoned the attempt (its outcome is the recorded
 *     timeout failure; the thread may still run)
 */
public record JobAttemptResult(
    JobClaim claim, JobAttemptOutcome outcome, Duration duration, boolean isAbandoned) {

  /** Checks the components. */
  public JobAttemptResult {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(duration, "duration");
  }
}
