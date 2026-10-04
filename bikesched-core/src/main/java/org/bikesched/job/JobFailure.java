package org.bikesched.job;

import java.time.Duration;
import org.bikesched.BikeschedBuilder;
import org.bikesched.RetryBackoff;

/**
 * An attempt a handler marked as failed via {@link Job#fail(String)}, with optional retry settings:
 *
 * <pre>{@code
 * job.fail("provider busy").remainingAttempts(2).retryAfter(Duration.ofMinutes(5));
 * return;
 * }</pre>
 *
 * <p>The settings are applied once the handler returns. May be configured from other threads than
 * the handler's.
 */
public interface JobFailure {

  /**
   * Sets how many more attempts the job gets after this one, regardless of its maximum number of
   * attempts so far: the job's new maximum is its current attempt number plus {@code
   * remainingAttempts}. With {@code 0}, the job is marked {@code FAILED} without further attempts.
   * Without calling this, the job's maximum number of attempts applies.
   *
   * @param remainingAttempts the number of further attempts, at least 0
   * @return this failure
   */
  JobFailure remainingAttempts(int remainingAttempts);

  /**
   * Delays the next attempt: the job is due again once the given time has passed from now (its
   * scheduled time is updated). Without calling this, the node's {@link
   * BikeschedBuilder#retryBackoff(RetryBackoff) retry backoff} applies (by default: retry as soon
   * as possible); {@link Duration#ZERO} retries as soon as possible regardless of it. Has no effect
   * if no attempts remain.
   *
   * @param delay the delay of the next attempt, not negative
   * @return this failure
   */
  JobFailure retryAfter(Duration delay);
}
