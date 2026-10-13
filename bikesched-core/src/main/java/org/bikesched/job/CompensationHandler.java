// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Undoes a completed step when its job is cancelled or fails permanently, see {@link
 * StepBuilder#compensateWith(CompensationHandler)}.
 *
 * @param <T> the type of the step result
 */
@FunctionalInterface
public interface CompensationHandler<T extends @Nullable Object> {

  /**
   * Undoes the step, e.g. releases a reserved payment. Runs at least once per completed step, so it
   * must be idempotent: if the node dies before the compensation is recorded, it runs again.
   *
   * @param step the step to undo
   * @param result the step's stored result, may be {@code null}
   * @throws Exception if the compensation failed; it runs again in the job's next compensation
   *     attempt
   */
  void handle(Step step, @Nullable T result) throws Exception;
}
