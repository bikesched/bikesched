// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job.listener;

import org.bikesched.BikeschedBuilder;

/**
 * Observes the jobs a node runs, e.g. for metrics ({@code bikesched-micrometer}); set with {@link
 * BikeschedBuilder#jobListener(JobListener)}. All methods do nothing by default.
 *
 * <p>Methods are called synchronously on the job's thread (for an abandoned attempt, on the thread
 * recording it), so they must be fast and thread-safe. Exceptions they throw are logged and
 * otherwise ignored.
 */
public interface JobListener {

  /**
   * Called when the node starts processing a claimed job, before its handler runs. Each call is
   * followed by exactly one {@link #onAttemptEnded(JobAttemptResult)} for the same claim.
   *
   * @param claim the claimed job
   */
  default void onJobClaimed(JobClaim claim) {}

  /**
   * Called once the outcome of an attempt was recorded (or could not be recorded, see {@link
   * org.bikesched.job.listener.JobAttemptOutcome#NOT_RECORDED}).
   *
   * @param result the attempt's result
   */
  default void onAttemptEnded(JobAttemptResult result) {}

  /**
   * Called when the thread of an attempt abandoned by the hard timeout (see {@link
   * BikeschedBuilder#hardTimeoutGrace(java.time.Duration)}) finally returns; its result was already
   * reported with {@link JobAttemptResult#isAbandoned()}.
   *
   * @param claim the abandoned attempt's claim
   */
  default void onAbandonedAttemptReturned(JobClaim claim) {}
}
