// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * Checks once whether the result of a poll is ready, see {@link Job#newPoll(String, Class,
 * java.time.Duration)}.
 *
 * @param <T> the type of the ready value
 */
@FunctionalInterface
public interface PollHandler<T extends @Nullable Object> {

  /**
   * Checks whether the result is ready, e.g. by asking an external service.
   *
   * @param step the poll's step: job id, step name, and the number of this check ({@link
   *     Step#attempts()}, starting at 1), e.g. for an idempotency key or a log line
   * @return {@link Poll#ready(Object)} with the value, or {@link Poll#notReady()}; not {@code null}
   * @throws Exception to fail the job's attempt (it is retried while attempts remain, and checks
   *     again right away)
   */
  Poll<T> handle(Step step) throws Exception;
}
