// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.time.Instant;
import java.util.UUID;
import org.bikesched.job.JobState;

/**
 * A step of a job, see {@link Admin#findSteps(UUID)}.
 *
 * @param name the step name
 * @param kind what the step does: {@code STEP}, {@code SLEEP}, {@code EVENT_WAIT}, {@code
 *     EVENT_PUBLISH}, {@code CHILD_JOB} or {@code POLL}
 * @param state the step's state, e.g. {@code RUNNING}, {@code WAITING} or {@code SUCCEEDED}
 * @param attempts the attempts of the step
 * @param lastError the error of its last failed attempt; may be {@code null}
 * @param startedAt when the step started
 * @param finishedAt when the step ended; {@code null} while it has not ended
 * @param waitUntil the deadline of a wait or the end of a sleep; {@code null} if there is none
 * @param eventName the event an event wait waits for or an event step published; {@code null} for
 *     other kinds
 * @param eventKey the key of that event; {@code null} for other kinds
 * @param childJobId the child job a {@code CHILD_JOB} step started; {@code null} for other kinds
 * @param childJobType the child job's type, kept after the child was deleted
 * @param childJobState the child's current state, or its final state once it ended; {@code null}
 *     for other kinds
 * @param childJobExists whether the child job still exists (it is deleted after the job retention)
 */
public record StepInfo(
    String name,
    String kind,
    String state,
    int attempts,
    String lastError,
    Instant startedAt,
    Instant finishedAt,
    Instant waitUntil,
    String eventName,
    String eventKey,
    UUID childJobId,
    String childJobType,
    JobState childJobState,
    boolean childJobExists) {}
