// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * Handles a job of one type that failed permanently, before it ends {@code FAILED} or {@code
 * COMPENSATION_FAILED}, e.g. to move it to a dead-letter table or to alert someone; registered via
 * {@link org.bikesched.Bikesched#registerJobFailureHandler(String, JobFailureHandler)}.
 *
 * <p>It runs whatever made the job fail (an exception, a timeout, an abandoned attempt, a dead
 * node, {@link Job#fail(String)} without remaining attempts, failed compensations), after the job's
 * compensations, in a claim of its own: the job is {@code FAILING} until the handler returned. A
 * handler that throws or times out runs again after the node's {@link org.bikesched.RetryBackoff},
 * up to {@link org.bikesched.BikeschedBuilder#failureHandlerAttempts(int) failureHandlerAttempts}
 * runs; then the job ends anyway. It runs at least once, so it must be idempotent. The job's
 * outcome is final: the handler only reacts to it.
 */
@FunctionalInterface
public interface JobFailureHandler {

  /**
   * Handles the failed job.
   *
   * @param job the failed job, with its payload and the stored error
   * @throws Exception if the handling failed; it runs again
   */
  void handle(FailedJob job) throws Exception;
}
