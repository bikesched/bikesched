// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * A step of a job, see {@link Admin#findSteps(UUID)} and {@link Admin#findStep(UUID)}.
 *
 * @param id the step id, created when the step first started and kept across its attempts
 * @param jobId the job the step belongs to
 * @param name the step name, unique within its job
 * @param type what the step does: {@code STEP}, {@code SLEEP}, {@code EVENT_WAIT}, {@code
 *     EVENT_PUBLISH}, {@code CHILD_JOB} or {@code POLL}
 * @param state the step's state, e.g. {@code RUNNING}, {@code WAITING} or {@code SUCCEEDED}
 * @param attempts the attempts of the step (the checks of a poll)
 * @param failureAttempts the runs of its failure handler while it was {@code FAILING}
 * @param compensable whether it has a compensation to run if its job is cancelled or fails
 * @param lastError the error of its last failed attempt; may be {@code null}
 * @param startedAt when the step (its last attempt) started
 * @param finishedAt when the step ended; {@code null} while it has not ended
 * @param waitUntil the deadline of a wait, the end of a sleep or the next check of a poll; {@code
 *     null} if there is none
 * @param deadline the deadline of a poll; {@code null} for other types and polls without a timeout
 * @param nodeId the node running the step; {@code null} unless it is {@code RUNNING}
 * @param eventName the event an event wait waits for or an event step published; {@code null} for
 *     other types
 * @param eventKey the key of that event; {@code null} for other types
 * @param eventId the event an event wait received or an event step published; {@code null} until
 *     then and for other types
 * @param childJobId the child job a {@code CHILD_JOB} step started; {@code null} for other types
 * @param childJobName the child job's name, kept after the child was deleted
 * @param childJobState the child's current state, or its final state once it ended; {@code null}
 *     for other types
 * @param childJobExists whether the child job still exists (it is deleted after the job retention)
 */
public record StepInfo(
    UUID id,
    UUID jobId,
    String name,
    String type,
    String state,
    int attempts,
    int failureAttempts,
    boolean compensable,
    @Nullable String lastError,
    Instant startedAt,
    @Nullable Instant finishedAt,
    @Nullable Instant waitUntil,
    @Nullable Instant deadline,
    @Nullable UUID nodeId,
    @Nullable String eventName,
    @Nullable String eventKey,
    @Nullable UUID eventId,
    @Nullable UUID childJobId,
    @Nullable String childJobName,
    @Nullable JobState childJobState,
    boolean childJobExists) {}
