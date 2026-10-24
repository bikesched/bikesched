// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job.listener;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * A job claimed by a node, reported to a {@link org.bikesched.job.listener.JobListener}.
 *
 * @param jobId the job's id
 * @param jobName the job's name
 * @param attempt the attempt (counted separately for compensation and for the failure handler)
 * @param compensation whether the job's steps are being compensated
 * @param failing whether the job's failure handler runs (the job is {@code FAILING})
 * @param delay how long after it was due the job was claimed, zero if not later
 */
public record JobClaim(
    UUID jobId,
    String jobName,
    int attempt,
    boolean compensation,
    boolean failing,
    Duration delay) {

  /** Checks the components. */
  public JobClaim {
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(jobName, "jobName");
    Objects.requireNonNull(delay, "delay");
  }
}
