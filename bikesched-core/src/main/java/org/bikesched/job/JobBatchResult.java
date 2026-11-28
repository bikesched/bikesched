// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of every item of a batch, see {@link JobBatch#awaitResult()}. Results are mapped from
 * JSON when they are read, per item. A failed item never fails the parent by itself when using
 * {@link JobBatch#awaitResult()}; the parent decides:
 *
 * <pre>{@code
 * List<Image> images = renders.await();                       // all or nothing (throws)
 * switch (renders.awaitResult()) {
 *     case JobBatchResult.Succeeded<Image> s -> publish(s.results());
 *     case JobBatchResult.Failed.Incomplete<Image> p -> publishPartial(p.succeeded(), p.unsucceeded());
 *     case JobBatchResult.Failed<Image> f -> job.fail("every item failed");
 * }
 * }</pre>
 *
 * <p>{@link Failed.Incomplete} extends {@link Failed}: handle it before {@link Failed} to tell
 * partial failure apart from total failure, or handle only {@link Succeeded} and {@link Failed} to
 * treat both the same.
 *
 * @param <R> the result type of the items
 */
public sealed interface JobBatchResult<R extends @Nullable Object>
    permits JobBatchResult.Succeeded, JobBatchResult.Failed {

  /**
   * Every item of the batch succeeded (also for an empty batch).
   *
   * @param <R> the result type of the items
   */
  non-sealed interface Succeeded<R extends @Nullable Object> extends JobBatchResult<R> {

    /**
     * Returns the results of all items in item order.
     *
     * @return the results, with {@code null} for items without a result
     */
    List<@Nullable R> results();
  }

  /**
   * At least one item of the batch did not succeed (failed, cancelled or timed out). When some
   * items still succeeded, the result is {@link Incomplete}; when none succeeded, it is a plain
   * {@code Failed}.
   *
   * @param <R> the result type of the items
   */
  non-sealed interface Failed<R extends @Nullable Object> extends JobBatchResult<R> {

    /**
     * Some items succeeded and at least one did not.
     *
     * @param <R> the result type of the items
     */
    interface Incomplete<R extends @Nullable Object> extends Failed<R> {}
  }

  /**
   * Returns whether every item succeeded.
   *
   * @return {@code true} if every item is {@link JobResult.Succeeded} (also for an empty batch)
   */
  boolean isAllSucceeded();

  /**
   * Returns the results of all items in item order if every item succeeded, otherwise throws.
   *
   * @return the results, with {@code null} for items without a result
   * @throws JobFailedException naming the keys of the items that did not succeed, with the outcome
   *     of the first one
   */
  List<@Nullable R> orElseThrow();

  /**
   * Returns the results of the items that succeeded.
   *
   * @return key to result, in item order
   */
  Map<String, @Nullable R> succeeded();

  /**
   * Returns the outcomes of the items that did not succeed.
   *
   * @return key to outcome ({@code Failed}, {@code Cancelled}, {@code TimedOut}, …), in item order
   */
  Map<String, JobResult<R>> unsucceeded();

  /**
   * Returns the outcome of every item.
   *
   * @return key to outcome, in item order
   */
  Map<String, JobResult<R>> all();

  /**
   * Returns the outcome of one item, typed by the batch (in a mixed batch: by the item's job if it
   * is a {@link ResultJob}, otherwise as {@code Object}).
   *
   * @param key the item's key
   * @return its outcome
   * @throws IllegalArgumentException if the batch has no such item
   */
  JobResult<R> get(String key);

  /**
   * Returns the outcome of one item of a mixed batch, typed by its job.
   *
   * @param key the item's key
   * @param job the item's job
   * @param <T> the result type
   * @return its outcome
   * @throws IllegalArgumentException if the batch has no such item, or the item is of another job
   */
  <T extends @Nullable Object> JobResult<T> get(String key, ResultJob<?, T> job);

  /**
   * Returns the outcome of one item with its result mapped to the given type.
   *
   * @param key the item's key
   * @param type the result type
   * @param <T> the result type
   * @return its outcome
   * @throws IllegalArgumentException if the batch has no such item
   */
  <T extends @Nullable Object> JobResult<T> get(String key, Class<T> type);
}
