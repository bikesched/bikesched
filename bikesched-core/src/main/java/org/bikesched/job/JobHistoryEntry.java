package org.bikesched.job;

import java.time.Instant;
import java.util.UUID;

/**
 * A recorded change of a job or of one of its steps, see {@link
 * org.bikesched.Bikesched#findJobHistory(UUID)}. Recorded only by nodes with the job history
 * enabled ({@code jobHistory(retention)} on the builder).
 *
 * @param createdAt when the change was made (the start of its transaction)
 * @param stepName the step that changed; {@code null} for a change of the job itself
 * @param state the job's new state (a {@link JobState} name) or the step's new state ({@code
 *     RUNNING}, {@code WAITING}, {@code SUCCEEDED}, {@code FAILED}, {@code TIMED_OUT}, {@code
 *     COMPENSATED} or {@code CANCELLED}); the unchanged state for recorded requests that do not
 *     change it, e.g. a cancel request or a failed compensation of a step
 * @param attempt the job's number of attempts after the change
 * @param nodeId the node that made the change; {@code null} if the application made it (scheduling,
 *     requesting a cancellation, publishing an event)
 * @param detail what happened, e.g. the error of a failed attempt or the deadline of an event wait;
 *     may be {@code null}
 */
public record JobHistoryEntry(
    Instant createdAt, String stepName, String state, int attempt, UUID nodeId, String detail) {

  /**
   * Returns whether this entry records a change of a step rather than of the job itself.
   *
   * @return {@code true} if {@link #stepName()} is set
   */
  public boolean isStepEntry() {
    return stepName != null;
  }
}
