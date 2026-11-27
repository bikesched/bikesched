// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of every item of a batch, see {@link JobBatch#await()}. Results are mapped from JSON
 * when they are read, per item. A failed item never fails the parent by itself; the parent decides:
 *
 * <pre>{@code
 * List<Image> images = renders.await().orElseThrow();        // all or nothing
 * Map<String, Image> rendered = renders.await().succeeded(); // use what worked
 * }</pre>
 *
 * @param <R> the result type of the items
 */
public interface JobBatchResult<R extends @Nullable Object> {

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
