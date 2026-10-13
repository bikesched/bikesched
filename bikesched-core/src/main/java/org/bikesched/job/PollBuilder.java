// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import org.bikesched.BikeschedException;
import org.jspecify.annotations.Nullable;

/**
 * Builds a poll, see {@link Job#newPoll(String, Class, Duration)}: an optional timeout and failure
 * handler, then {@link #run(PollHandler)}.
 *
 * @param <T> the type of the ready value
 */
public interface PollBuilder<T extends @Nullable Object> {

  /**
   * Stops polling after the given time, counted from the first check: once a check after the
   * deadline is not ready, the poll times out and {@link #run(PollHandler)} throws a {@link
   * PollTimeoutException}; the job then fails without further attempts, unless a {@link
   * #onFailure(StepFailureHandler) failure handler} resolves the poll. The last check runs at the
   * deadline. Without a timeout, the poll checks until it is ready or the job is cancelled.
   *
   * <p>The deadline stored on the first check wins: a changed timeout on later runs does not move
   * it.
   *
   * @param timeout how long to poll at most, positive
   * @return this builder
   * @throws IllegalArgumentException if the timeout is not positive
   */
  PollBuilder<T> timeout(Duration timeout);

  /**
   * Sets a handler that runs when the poll failed (optional): once it {@link #timeout(Duration)
   * timed out}, right away (polling again would time out again), or when a check threw in the last
   * attempt of its job, like a step's {@link StepBuilder#onFailure(StepFailureHandler) failure
   * handler}. The handler may {@linkplain FailingStep#resolve(Object) resolve} the poll with a
   * fallback result instead, which {@link #run(PollHandler)} returns (stored like a ready value),
   * so the job continues. The step is {@code FAILING} meanwhile; a handler that throws runs again
   * in an extra attempt of the job, up to the node's {@link
   * org.bikesched.BikeschedBuilder#failureHandlerAttempts(int) failureHandlerAttempts} runs. Like
   * every failure handler, it cannot run steps, waits or polls.
   *
   * @param handler the failure handler, must be idempotent
   * @return this builder
   */
  PollBuilder<T> onFailure(StepFailureHandler<T> handler);

  /**
   * Runs the poll: returns the ready value (from this run's check, or stored by an earlier run), or
   * the fallback result its failure handler resolved it with. If a check is not ready, the job's
   * attempt ends here (the method does not return) and the job is suspended in state {@code
   * WAITING} until the next check.
   *
   * @param handler checks once whether the result is ready
   * @return the ready value, or the fallback result
   * @throws PollTimeoutException if the poll timed out and no failure handler resolved it; the job
   *     fails
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   * @throws Exception what the poll handler threw, unless a failure handler resolved the poll
   */
  @Nullable T run(PollHandler<T> handler) throws Exception;
}
