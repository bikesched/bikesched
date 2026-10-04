package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.job.JobFailure;

/** The {@link JobFailure} implementation; read by the {@link JobProcessor}. */
public final class DefaultJobFailure implements JobFailure {

  private final String message;
  private volatile Integer remainingAttempts;
  private volatile Duration retryAfter;

  /**
   * Creates the failure of an attempt.
   *
   * @param message the message passed to {@code Job.fail}, may be {@code null}
   */
  public DefaultJobFailure(String message) {
    this.message = message;
  }

  @Override
  public JobFailure remainingAttempts(int remainingAttempts) {
    if (remainingAttempts < 0) {
      throw new IllegalArgumentException("remainingAttempts must be >= 0: " + remainingAttempts);
    }
    this.remainingAttempts = remainingAttempts;
    return this;
  }

  @Override
  public JobFailure retryAfter(Duration delay) {
    Objects.requireNonNull(delay, "delay");
    if (delay.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative: " + delay);
    }
    this.retryAfter = delay;
    return this;
  }

  /** The message passed to {@code Job.fail}, {@code null} if none. */
  String message() {
    return message;
  }

  /** The number of further attempts, {@code null} if not set. */
  Integer remainingAttempts() {
    return remainingAttempts;
  }

  /** The delay of the next attempt, {@code null} if not set. */
  Duration retryDelay() {
    return retryAfter;
  }
}
