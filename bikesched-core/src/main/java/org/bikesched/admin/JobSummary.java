// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.bikesched.job.JobState;

/**
 * A job as listed by {@link Admin#findJobs(JobQuery)}, without its payload and result (see {@link
 * Admin#findJobData(UUID)}).
 *
 * @param id the job id
 * @param type the job type
 * @param state the current state
 * @param attempts the attempts made so far
 * @param maxAttempts the attempts allowed
 * @param priority the priority (higher runs first)
 * @param scheduledAt when the job is due; {@code null} while it waits without a deadline
 * @param createdAt when the job was scheduled
 * @param updatedAt when the job last changed
 * @param finishedAt when the job ended; {@code null} while it has not ended
 * @param lastError the error of the last failed attempt or the cancel message; may be {@code null}
 * @param parentJobId the job that started this one as a child job; {@code null} for other jobs
 * @param parentJobType the parent's type; {@code null} if there is no parent or it was deleted
 * @param recurrence the description of the recurrence of a recurring job's occurrence; {@code null}
 *     for other jobs
 * @param idempotencyKey the key the job was scheduled with; may be {@code null}
 * @param nodeName the node running the job; {@code null} unless it is running
 */
public record JobSummary(
    UUID id,
    String type,
    JobState state,
    int attempts,
    int maxAttempts,
    int priority,
    Instant scheduledAt,
    Instant createdAt,
    Instant updatedAt,
    Instant finishedAt,
    String lastError,
    UUID parentJobId,
    String parentJobType,
    String recurrence,
    String idempotencyKey,
    String nodeName) {}
