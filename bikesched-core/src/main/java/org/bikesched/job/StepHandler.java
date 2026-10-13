// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Executes a step of a job, see {@link Job#step(String, Class, StepHandler)}.
 *
 * @param <T> the type of the step result
 */
@FunctionalInterface
public interface StepHandler<T extends @Nullable Object> {

  /**
   * Executes the step. Only called if the step has not completed in an earlier attempt of the job.
   *
   * @param step the step being executed
   * @return the step result, stored durably (as JSON) and returned on later attempts of the job
   *     instead of executing the step again; may be {@code null}
   * @throws Exception if the step failed; it is executed again in the job's next attempt
   */
  @Nullable T handle(Step step) throws Exception;
}
