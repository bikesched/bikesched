// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailure;

/**
 * Delays the retry of a failed job attempt, configured per node via {@link
 * BikeschedBuilder#retryBackoff(RetryBackoff)}. Applies to every failed attempt that is retried:
 * the handler threw, timed out, or called {@link Job#fail(String)} without {@link
 * JobFailure#retryAfter(Duration)} (which takes precedence).
 *
 * <pre>{@code
 * Bikesched.builder()
 *     .dataSource(dataSource)
 *     .retryBackoff(RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(10)))
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface RetryBackoff {

  /**
   * Returns the delay of the next attempt after a failed one.
   *
   * @param failedAttempt the number of the attempt that failed, starting at 1
   * @return the delay from now, zero to retry as soon as possible; must not be negative
   */
  Duration delay(int failedAttempt);

  /**
   * Retries as soon as possible, i.e. on the next poll (the default).
   *
   * @return the backoff
   */
  static RetryBackoff none() {
    return ignored -> Duration.ZERO;
  }

  /**
   * Waits the same delay before every retry.
   *
   * @param delay the delay, not negative
   * @return the backoff
   */
  static RetryBackoff fixed(Duration delay) {
    requireNotNegative(delay, "delay");
    return ignored -> delay;
  }

  /**
   * Doubles the delay with every failed attempt, starting with {@code initialDelay} after the first
   * one and capped at {@code maxDelay}: e.g. 5s, 10s, 20s, 40s, ... up to the maximum. Each delay
   * is randomly shortened by up to half (jitter), e.g. 10s becomes 5 to 10s, so jobs that failed
   * together (e.g. during an outage) don't all retry at the same moment.
   *
   * @param initialDelay the delay after the first failed attempt, positive
   * @param maxDelay the longest delay, at least {@code initialDelay}
   * @return the backoff
   */
  static RetryBackoff exponential(Duration initialDelay, Duration maxDelay) {
    requireNotNegative(initialDelay, "initialDelay");
    requireNotNegative(maxDelay, "maxDelay");
    if (initialDelay.isZero()) {
      throw new IllegalArgumentException("initialDelay must be positive");
    }
    if (maxDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException(
          "maxDelay ("
              + maxDelay
              + ") must not be shorter than initialDelay ("
              + initialDelay
              + ")");
    }
    return failedAttempt -> {
      Duration delay = initialDelay;
      for (int i = 1; i < failedAttempt && delay.compareTo(maxDelay) < 0; i++) {
        delay = delay.multipliedBy(2);
      }
      long nanos = (delay.compareTo(maxDelay) > 0 ? maxDelay : delay).toNanos();
      return Duration.ofNanos(nanos - ThreadLocalRandom.current().nextLong(nanos / 2 + 1));
    };
  }

  private static void requireNotNegative(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative: " + duration);
    }
  }
}
