// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

import java.time.Instant;
import java.util.UUID;
import org.bikesched.job.StepState;
import org.jspecify.annotations.Nullable;

/**
 * A change of a step of a job, see {@link HistoryEntry}. The step's name is in {@link #data()}.
 *
 * @param id the entry's id
 * @param jobId the step's job
 * @param stepId the step
 * @param createdAt when the entry was written
 * @param action what happened
 * @param state the step's state afterwards
 * @param attempt the job's number of attempts afterwards
 * @param actorType who made the change
 * @param actorName the user name for {@link ActorType#USER}, otherwise {@code null}
 * @param nodeId the node that wrote the entry, {@code null} if none
 * @param causedById the event, job or node that caused the change, {@code null} if none
 * @param data the names and the action's specifics as a JSON object, see {@link
 *     HistoryEntry#data()}
 */
public record StepEntry(
    UUID id,
    UUID jobId,
    UUID stepId,
    Instant createdAt,
    StepAction action,
    StepState state,
    int attempt,
    ActorType actorType,
    @Nullable String actorName,
    @Nullable UUID nodeId,
    @Nullable UUID causedById,
    @Nullable String data)
    implements HistoryEntry {
  @Override
  public UUID subjectId() {
    return jobId;
  }
}
