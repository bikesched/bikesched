// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one check of a poll, returned by a {@link PollHandler}: the result is {@link
 * #ready(Object) ready}, or {@link #notReady() not ready} yet, so the poll checks again later.
 *
 * <pre>{@code
 * Report report = job.newPoll("await-report", Report.class, Duration.ofSeconds(30))
 *     .run(step -> switch (reports.status(reportId)) {
 *       case Done d -> Poll.ready(d.report());
 *       case Pending pending -> Poll.notReady();
 *     });
 * }</pre>
 *
 * <p>An expected negative answer of the service (e.g. "the report failed") is a ready value too,
 * e.g. a sealed result type; a check that throws fails the job's attempt instead.
 *
 * @param <T> the type of the ready value
 */
public sealed interface Poll<T extends @Nullable Object> {

  /**
   * Returns a ready outcome: the poll completes with the value, which is stored and returned on
   * later runs of the handler.
   *
   * @param value the value, may be {@code null}
   * @param <T> the type of the value
   * @return the outcome
   */
  static <T extends @Nullable Object> Poll<T> ready(@Nullable T value) {
    return new Ready<>(value);
  }

  /**
   * Returns a not-ready outcome: the poll checks again after its interval.
   *
   * @param <T> the type of the ready value
   * @return the outcome
   */
  static <T extends @Nullable Object> Poll<T> notReady() {
    return new NotReady<>(null);
  }

  /**
   * Returns a not-ready outcome with the time until the next check, instead of the poll's interval
   * (for this check only), e.g. from a {@code Retry-After} header.
   *
   * @param retryAfter the time until the next check, not negative
   * @param <T> the type of the ready value
   * @return the outcome
   * @throws IllegalArgumentException if {@code retryAfter} is negative
   */
  static <T extends @Nullable Object> Poll<T> notReady(Duration retryAfter) {
    return new NotReady<>(Objects.requireNonNull(retryAfter, "retryAfter"));
  }

  /**
   * The result is ready.
   *
   * @param value the value, may be {@code null}
   * @param <T> the type of the value
   */
  record Ready<T extends @Nullable Object>(@Nullable T value) implements Poll<T> {}

  /**
   * The result is not ready yet.
   *
   * @param retryAfter the time until the next check; {@code null} for the poll's interval
   * @param <T> the type of the ready value
   */
  record NotReady<T extends @Nullable Object>(@Nullable Duration retryAfter) implements Poll<T> {

    /**
     * Validates the delay.
     *
     * @throws IllegalArgumentException if {@code retryAfter} is negative
     */
    public NotReady {
      if (retryAfter != null && retryAfter.isNegative()) {
        throw new IllegalArgumentException("retryAfter must not be negative: " + retryAfter);
      }
    }
  }
}
