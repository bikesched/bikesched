// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * Handles a step that failed in the last attempt of its job, see {@link
 * StepBuilder#onFailure(StepFailureHandler)}.
 *
 * @param <T> the type of the step result
 */
@FunctionalInterface
public interface StepFailureHandler<T> {

  /**
   * Handles the failure, e.g. marks an order as failed, or {@linkplain FailingStep#resolve(Object)
   * resolves} the step with a fallback result so the job continues. The step is {@code FAILING}
   * until this returns, then {@code FAILED} (and the job fails permanently) or, if resolved, {@code
   * SUCCEEDED}. If the handler throws, or the node dies before the outcome is recorded, it runs
   * again in an extra attempt of the job, up to the node's {@link
   * org.bikesched.BikeschedBuilder#failureHandlerAttempts(int) failureHandlerAttempts} runs, so it
   * must be idempotent.
   *
   * @param step the failing step, with the stored description of the failure
   * @throws Exception if the handling failed; it runs again in the job's next attempt
   */
  void handle(FailingStep<T> step) throws Exception;
}
