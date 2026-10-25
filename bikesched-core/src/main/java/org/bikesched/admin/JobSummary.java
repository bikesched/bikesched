// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.jspecify.annotations.Nullable;

/**
 * A job as listed by {@link Admin#findJobs(JobQuery)}, without its payload and result (see {@link
 * Admin#findJobData(UUID)}).
 *
 * @param id the job id
 * @param name the job name
 * @param type how the job was started: one-off, an occurrence of a recurring job or a child job
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
 * @param parentJobName the parent's name; {@code null} if there is no parent or it was deleted
 * @param recurrence the description of the recurrence of a recurring job's occurrence; {@code null}
 *     for other jobs
 * @param idempotencyKey the key the job was scheduled with; may be {@code null}
 * @param reference the external entity the job is about; may be {@code null}
 * @param nodeId the node running the job; {@code null} unless it is running
 * @param nodeName that node's name; {@code null} unless it is running
 */
public record JobSummary(
    UUID id,
    String name,
    JobType type,
    JobState state,
    int attempts,
    int maxAttempts,
    int priority,
    @Nullable Instant scheduledAt,
    Instant createdAt,
    Instant updatedAt,
    @Nullable Instant finishedAt,
    @Nullable String lastError,
    @Nullable UUID parentJobId,
    @Nullable String parentJobName,
    @Nullable String recurrence,
    @Nullable String idempotencyKey,
    @Nullable String reference,
    @Nullable UUID nodeId,
    @Nullable String nodeName) {}
