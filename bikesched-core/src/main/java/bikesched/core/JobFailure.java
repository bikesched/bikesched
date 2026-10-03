package bikesched.core;

import java.time.Duration;
import java.util.Objects;

/**
 * An attempt a handler marked as failed via {@link Job#fail(String)}, with optional retry settings:
 *
 * <pre>{@code
 * job.fail("provider busy").retries(2).retryAfter(Duration.ofMinutes(5));
 * return;
 * }</pre>
 *
 * <p>The settings are applied once the handler returns. May be configured from other threads than
 * the handler's.
 */
public final class JobFailure {

  private final String message;
  private volatile Integer retries;
  private volatile Duration retryAfter;

  JobFailure(String message) {
    this.message = message;
  }

  /**
   * Sets how many more attempts the job gets after this one, regardless of its maximum number of
   * attempts so far: the job's new maximum is its current attempt number plus {@code retries}. With
   * {@code 0}, the job is marked {@code FAILED} without further attempts. Without calling this, the
   * job's maximum number of attempts applies.
   *
   * @param retries the number of further attempts, at least 0
   * @return this failure
   */
  public JobFailure retries(int retries) {
    if (retries < 0) {
      throw new IllegalArgumentException("retries must be >= 0: " + retries);
    }
    this.retries = retries;
    return this;
  }

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
  public JobFailure retryAfter(Duration delay) {
    Objects.requireNonNull(delay, "delay");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative: " + delay);
    }
    this.retryAfter = delay;
    return this;
  }

  /** The message passed to {@link Job#fail(String)}, {@code null} if none. */
  String message() {
    return message;
  }

  /** The number of further attempts, {@code null} if not set. */
  Integer additionalAttempts() {
    return retries;
  }

  /** The delay of the next attempt, {@code null} if not set. */
  Duration retryDelay() {
    return retryAfter;
  }
}
