// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import org.bikesched.job.JobFailure;
import org.jspecify.annotations.Nullable;

/** The {@link JobFailure} implementation; read by the {@link JobProcessor}. */
public final class DefaultJobFailure implements JobFailure {

  private final @Nullable String message;
  private volatile @Nullable Integer remainingAttempts;
  private volatile @Nullable Duration retryAfter;

  /**
   * Creates the failure of an attempt.
   *
   * @param message the message passed to {@code Job.fail}, may be {@code null}
   */
  public DefaultJobFailure(@Nullable String message) {
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
  @Nullable String message() {
    return message;
  }

  /** The number of further attempts, {@code null} if not set. */
  @Nullable Integer remainingAttempts() {
    return remainingAttempts;
  }

  /** The delay of the next attempt, {@code null} if not set. */
  @Nullable Duration retryDelay() {
    return retryAfter;
  }
}
