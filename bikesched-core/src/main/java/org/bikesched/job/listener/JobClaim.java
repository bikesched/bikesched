// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job.listener;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A job claimed by a node, reported to a {@link org.bikesched.job.listener.JobListener}.
 *
 * @param jobId the job's id
 * @param jobName the job's name
 * @param reference the job's {@linkplain org.bikesched.job.Job#reference() reference}, {@code null}
 *     if it has none; one value per business entity, so not suited as a metric tag
 * @param attempt the attempt (counted separately for compensation and for the failure handler)
 * @param compensation whether the job's steps are being compensated
 * @param failing whether the job's failure handler runs (the job is {@code FAILING})
 * @param delay how long after it was due the job was claimed, zero if not later
 */
public record JobClaim(
    UUID jobId,
    String jobName,
    @Nullable String reference,
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
