// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Handles jobs of a {@link ResultJob} and returns their result, which bikesched stores like {@link
 * Job#complete(Object)}: cancelling or failing the job in the same attempt still wins. Like a
 * {@link TypedJobHandler} otherwise.
 *
 * @param <T> the payload type
 * @param <R> the result type
 * @see org.bikesched.Bikesched#registerResultJobHandler(ResultJob, ResultJobHandler)
 */
@FunctionalInterface
public interface ResultJobHandler<T extends @Nullable Object, R extends @Nullable Object> {

  /**
   * Handles a job.
   *
   * @param job the job being executed
   * @param payload the job's payload mapped by the {@code JsonMapper}, {@code null} if it has none
   * @return the job's result, mapped to JSON by the {@code JsonMapper}; {@code null} for none
   * @throws Exception to fail the attempt
   */
  R handle(Job job, @Nullable T payload) throws Exception;
}
