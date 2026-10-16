// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a child job a parent waited for with {@link Job#waitForJob(UUID, Class)}: one
 * record per state the child ended in, plus {@link TimedOut} when the parent's wait timed out
 * before the child ended. A failed child never fails its parent by itself; the parent decides:
 *
 * <pre>{@code
 * Receipt receipt = switch (job.waitForJob(chargeId, Receipt.class)) {
 *     case JobResult.Succeeded<Receipt>(var id, Receipt r) -> r;
 *     case JobResult.Failed<Receipt> f -> throw new IllegalStateException(f.error());
 *     case JobResult.Cancelled<Receipt> c -> { job.cancel(c.message()); yield null; }
 *     case JobResult.CompensationFailed<Receipt> c -> throw new IllegalStateException(c.error());
 *     case JobResult.TimedOut<Receipt> t -> throw new IllegalStateException("no timeout set");
 * };
 * Parcel parcel = job.waitForJob(packId, Parcel.class).orElseThrow(); // or simply this
 * Label label = job.waitForJob(labelId, Label.class).orElse(Label.NONE); // or with a fallback
 * }</pre>
 *
 * <p>A child that was cancelled or failed after its compensations ran is {@link Cancelled} resp.
 * {@link Failed}.
 *
 * @param <T> the type of the child's result
 */
public sealed interface JobResult<T extends @Nullable Object> {

  /**
   * Returns the id of the child job.
   *
   * @return the child's id
   */
  UUID jobId();

  /**
   * The child succeeded.
   *
   * @param jobId the child's id
   * @param result the result it set with {@link Job#complete(Object)}, {@code null} if none
   * @param <T> the type of the result
   */
  record Succeeded<T extends @Nullable Object>(UUID jobId, @Nullable T result)
      implements JobResult<T> {}

  /**
   * The child failed permanently (after its last attempt, and its compensations if any).
   *
   * @param jobId the child's id
   * @param error the error of its last attempt
   * @param <T> the type of the result
   */
  record Failed<T extends @Nullable Object>(UUID jobId, String error) implements JobResult<T> {}

  /**
   * The child was cancelled, by its handler or via {@code Bikesched.cancel}.
   *
   * @param jobId the child's id
   * @param message the cancel message, {@code null} if none
   * @param <T> the type of the result
   */
  record Cancelled<T extends @Nullable Object>(UUID jobId, @Nullable String message)
      implements JobResult<T> {}

  /**
   * Compensating the child's steps failed permanently.
   *
   * @param jobId the child's id
   * @param error the error of its last compensation attempt
   * @param <T> the type of the result
   */
  record CompensationFailed<T extends @Nullable Object>(UUID jobId, String error)
      implements JobResult<T> {}

  /**
   * The parent's wait timed out before the child ended (see {@link Job#waitForJob(UUID, Class,
   * java.time.Duration)}); the child is not touched and may still run.
   *
   * @param jobId the child's id
   * @param <T> the type of the result
   */
  record TimedOut<T extends @Nullable Object>(UUID jobId) implements JobResult<T> {}

  /**
   * Returns whether the child succeeded.
   *
   * @return {@code true} for {@link Succeeded}
   */
  default boolean isSucceeded() {
    return this instanceof Succeeded<T>;
  }

  /**
   * Returns the child's result if it succeeded, otherwise the fallback: for a failed, cancelled or
   * compensation-failed child, and when the wait timed out.
   *
   * @param fallback the value to return unless the child succeeded, may be {@code null}
   * @return the result ({@code null} if the child set none), or the fallback
   */
  default @Nullable T orElse(@Nullable T fallback) {
    if (this instanceof Succeeded<T>(UUID ignored, T result)) {
      return result;
    }
    return fallback;
  }

  /**
   * Returns the child's result if it succeeded, otherwise throws.
   *
   * @return the result, {@code null} if the child set none
   * @throws JobFailedException with this outcome if the child did not succeed or the wait timed out
   */
  default @Nullable T orElseThrow() {
    if (this instanceof Succeeded<T>(UUID ignored, T result)) {
      return result;
    }
    throw new JobFailedException(this);
  }
}
