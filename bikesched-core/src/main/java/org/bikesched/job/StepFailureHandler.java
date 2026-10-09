// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * Handles a step that failed in the last attempt of its job, see {@link
 * StepBuilder#onFailure(StepFailureHandler)}.
 */
@FunctionalInterface
public interface StepFailureHandler {

  /**
   * Handles the failure, e.g. marks an order as failed. The step is {@code FAILING} until this
   * returns, then {@code FAILED} and the job fails permanently. If the handler throws, or the node
   * dies before the step is recorded as failed, it runs again in an extra attempt of the job, so it
   * must be idempotent.
   *
   * @param step the failed step
   * @param failure the exception the step threw; if the handler runs again in a later attempt, a
   *     {@link org.bikesched.BikeschedException} carrying the stored description of the failure
   * @throws Exception if the handling failed; it runs again in the job's next attempt
   */
  void handle(Step step, Exception failure) throws Exception;
}
