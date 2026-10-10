// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.UUID;

/**
 * A step that failed in the last attempt of its job, handed to its {@link StepFailureHandler}.
 *
 * @param <T> the type of the step result
 */
public interface FailingStep<T> {

  /**
   * Returns the id of the step's job.
   *
   * @return the job id
   */
  UUID jobId();

  /**
   * Returns the step name.
   *
   * @return the name, unique within the job
   */
  String name();

  /**
   * Returns the description of the failure stored with the step (the exception's {@code
   * toString()}, truncated); the same on every run of the handler, also after a restart.
   *
   * @return the stored error
   */
  String error();

  /**
   * Resolves the step with a fallback result: once the handler returns, the step is {@code
   * SUCCEEDED} with this result (stored and returned by later runs like any step result) and the
   * job continues. A resolved step is never compensated, its side effect failed. Takes effect even
   * if the handler throws afterwards.
   *
   * @param result the step result, may be {@code null}
   */
  void resolve(T result);
}
