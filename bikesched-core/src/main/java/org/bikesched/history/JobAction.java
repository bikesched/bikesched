// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/**
 * What happened to a job, see {@link JobEntry}; {@link JobEntry#state()} is its state afterwards.
 * Every job entry's {@linkplain HistoryEntry#data() data} has the {@code job} with its {@code
 * name}; the keys each action may carry besides are listed with it.
 */
public enum JobAction implements HistoryAction {

  /**
   * Scheduled: a new job, an occurrence of a recurring job or a child job. Data: {@code dueAt},
   * {@code recurrence}, {@code idempotencyKey}, {@code reference}.
   */
  SCHEDULED,

  /**
   * Claimed by a node ({@code RUNNING}). Data: {@code reason} ({@code COMPENSATION}, {@code
   * FAILURE_HANDLER} or {@code CANCELLATION}, absent for a regular attempt) and {@code run} (the
   * compensation attempt or failure handler run).
   */
  CLAIMED,

  /** Completed successfully. */
  SUCCEEDED,

  /** An attempt failed and the job is retried. Data: {@code error}, {@code dueAt}. */
  ATTEMPT_FAILED,

  /**
   * Failed permanently: its last attempt failed, or it ended after its failure handler. Data:
   * {@code error}, {@code reason} after a failure handler ({@code NO_FAILURE_HANDLER}, {@code
   * FAILURE_HANDLER_RAN} or {@code FAILURE_HANDLER_GAVE_UP}).
   */
  FAILED,

  /**
   * Its attempt suspended at a waiting step ({@code WAITING}, or {@code SCHEDULED} if it is due
   * again right away). Data: {@code step} ({@code name}), {@code waitUntil}.
   */
  SUSPENDED,

  /** Resumed while {@code WAITING}: an event arrived or a child job ended. */
  RESUMED,

  /**
   * Postponed by its handler. Data: {@code dueAt}, {@code step} (the postponing step's {@code
   * name}).
   */
  POSTPONED,

  /** Retried after it failed. */
  RETRIED,

  /** Its cancellation was requested; its state is unchanged. Data: {@code message}. */
  CANCEL_REQUESTED,

  /** Cancelled. Data: {@code message}. */
  CANCELLED,

  /**
   * Released from its node without an outcome and due again. Data: {@code reason} ({@code
   * NODE_STOPPED}, {@code NODE_DEAD} or {@code OUTCOME_NOT_RECORDED}).
   */
  RELEASED,

  /**
   * A pending occurrence moved to the changed recurrence of its recurring job. Data: {@code
   * recurrence}, {@code previousRecurrence}, {@code dueAt}.
   */
  MOVED,

  /** A pending occurrence deleted with its recurring job; its state is the one it had. */
  DELETED,

  /** Failed permanently; its failure handler runs before it ends. Data: {@code error}. */
  FAILING,

  /** Its failure handler failed and runs again. Data: {@code error}, {@code dueAt}. */
  FAILURE_HANDLER_FAILED,

  /**
   * Cancelled or failed permanently; its completed steps are compensated before it ends. Data:
   * {@code error} or {@code message}.
   */
  COMPENSATING,

  /** Its compensations ran; it ends (or runs its failure handler first). */
  COMPENSATED,

  /**
   * A compensation attempt failed: compensating again ({@code COMPENSATING}), or for good. Data:
   * {@code error}, {@code dueAt} if it compensates again; {@code reason} after a failure handler,
   * as for {@link #FAILED}.
   */
  COMPENSATION_FAILED
}
