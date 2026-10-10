// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job.listener;

/** What was recorded for an attempt, see {@link JobAttemptResult}. */
public enum JobAttemptOutcome {
  /** The job succeeded. */
  SUCCEEDED,
  /** The attempt failed, another attempt is scheduled. */
  RETRYING,
  /** The job failed permanently. */
  FAILED,
  /** The job was cancelled (by its handler, or on request before its handler ran). */
  CANCELLED,
  /** The job waits for an event (or was rescheduled because the event arrived meanwhile). */
  WAITING,
  /** The job failed permanently or was cancelled, its completed steps are compensated next. */
  COMPENSATING,
  /** The compensation of the job's steps completed. */
  COMPENSATED,
  /** The compensation attempt failed, another one is scheduled. */
  COMPENSATION_RETRYING,
  /** The compensation failed permanently. */
  COMPENSATION_FAILED,
  /**
   * The job failed permanently (or its compensation did) and has a failure handler: the job is
   * {@code FAILING} until the handler ran; that attempt then reports {@link #FAILED} or {@link
   * #COMPENSATION_FAILED}.
   */
  FAILING,
  /** The failure handler failed, it runs again. */
  FAILURE_HANDLER_RETRYING,
  /**
   * Nothing was recorded: the job was released while it ran (e.g. after missed heartbeats) or the
   * database write failed. The job runs again.
   */
  NOT_RECORDED
}
