// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * A job claimed by a node, reported to a {@link org.bikesched.JobListener}.
 *
 * @param jobId the job's id
 * @param jobType the job's type
 * @param attempt the attempt (counted separately for compensation)
 * @param compensation whether the job's steps are being compensated
 * @param delay how long after it was due the job was claimed, zero if not later
 */
public record JobClaim(
    UUID jobId, String jobType, int attempt, boolean compensation, Duration delay) {

  /** Checks the components. */
  public JobClaim {
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(jobType, "jobType");
    Objects.requireNonNull(delay, "delay");
  }
}
