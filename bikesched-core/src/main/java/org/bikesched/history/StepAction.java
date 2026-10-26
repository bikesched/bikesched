// SPDX-License-Identifier: Apache-2.0
package org.bikesched.history;

/**
 * What happened to a step of a job, see {@link StepEntry}; {@link StepEntry#state()} is its state
 * afterwards. Every step entry's {@linkplain HistoryEntry#data() data} has the {@code stepName};
 * further keys are listed with the actions.
 */
public enum StepAction implements HistoryAction {

  /** An attempt of a step started. Data: {@code run} (the step's attempt, from the second on). */
  STARTED,

  /** Completed with its result. Data: {@code checks} for a poll. */
  SUCCEEDED,

  /**
   * Failed. Data: {@code error}, {@code checks} for a poll, {@code reason} after its failure
   * handler ({@code FAILURE_HANDLER_RAN} or {@code FAILURE_HANDLER_GAVE_UP} with {@code run}).
   */
  FAILED,

  /**
   * Waits: a sleep, an event wait, a wait for a child job or a poll. Data: {@code waitUntil},
   * {@code eventName} and {@code eventKey}, {@code childJobId}, {@code checks}.
   */
  WAITING,

  /** Received the event it waited for. */
  RECEIVED,

  /** Started a child job and waits for it. Data: {@code childJobId}. */
  CHILD_STARTED,

  /** The child job it waited for ended. Data: {@code childState}. */
  CHILD_ENDED,

  /** A sleep's wake-up time has passed. Data: {@code waitUntil}. */
  WOKE_UP,

  /** A wait or poll reached its deadline. Data: {@code checks} for a poll. */
  TIMED_OUT,

  /** Its handler postponed the job. Data: {@code dueAt}. */
  POSTPONED,

  /** Failed (or timed out) for good; its failure handler runs. Data: {@code error}. */
  FAILING,

  /** Resolved by its failure handler with a fallback result. */
  RESOLVED,

  /** Published an event. Data: {@code eventId}. */
  PUBLISHED,

  /** Cancelled with its job before it completed. */
  CANCELLED,

  /** Its compensation ran. */
  COMPENSATED,

  /** Its compensation failed and runs again; its state is unchanged. Data: {@code error}. */
  COMPENSATION_FAILED
}
