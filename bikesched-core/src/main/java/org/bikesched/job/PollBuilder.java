// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import org.bikesched.BikeschedException;
import org.bikesched.event.TimeoutHandler;

/**
 * Builds a poll, see {@link Job#newPoll(String, Class, Duration)}: an optional timeout, then {@link
 * #run(PollHandler)}.
 *
 * @param <T> the type of the ready value
 */
public interface PollBuilder<T> {

  /**
   * Stops polling after the given time, counted from the first check: once a check after the
   * deadline is not ready, the poll times out and {@link #run(PollHandler)} throws a {@link
   * PollTimeoutException}; the job then fails without further attempts. The last check runs at the
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
   * Stops polling after the given time, like {@link #timeout(Duration)}, but then returns the
   * handler's result from {@link #run(PollHandler)} instead of failing the job. Unlike a ready
   * value, it is not stored: the timeout handler runs every time the job's handler reaches the
   * timed-out poll, so it should only produce a fallback, or run a {@link Job#step(String, Class,
   * StepHandler) step} for one with side effects.
   *
   * @param timeout how long to poll at most, positive
   * @param handler produces the result after the timeout
   * @return this builder
   * @throws IllegalArgumentException if the timeout is not positive
   */
  PollBuilder<T> timeout(Duration timeout, TimeoutHandler<? extends T> handler);

  /**
   * Runs the poll: returns the ready value (from this run's check, or stored by an earlier run), or
   * the timeout handler's result once the poll timed out. If a check is not ready, the job's
   * attempt ends here (the method does not return) and the job is suspended in state {@code
   * WAITING} until the next check.
   *
   * @param handler checks once whether the result is ready
   * @return the ready value, or the timeout handler's result
   * @throws PollTimeoutException if the poll timed out without a timeout handler; the job fails
   * @throws IllegalStateException if the step name is used for another step of the job, or if
   *     called while a step handler or compensation of the job runs
   * @throws BikeschedException if the step cannot be read or stored
   * @throws Exception what the poll handler (or timeout handler) threw
   */
  T run(PollHandler<T> handler) throws Exception;
}
