// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Builds a batch of child jobs of one job, started as one step of its parent with {@link
 * #schedule()}, see {@link Job#newJobBatch(String, ResultJob)}.
 *
 * <p>Items are added in order, with a key or keyed by their position ({@code "0"}, {@code "1"}, …).
 * A key names the item's step ({@code stepName/key}), keys the results and shows in the admin UI;
 * it has 1 to 200 characters and is unique within the batch.
 *
 * @param <P> the payload type of the items
 * @param <R> the result type of the items
 */
public interface JobBatchBuilder<P extends @Nullable Object, R extends @Nullable Object>
    extends JobBatchSettings<JobBatchBuilder<P, R>> {

  /**
   * Adds an item keyed by its position.
   *
   * @param payload the item's payload, may be {@code null}
   * @return this builder
   */
  JobBatchBuilder<P, R> add(@Nullable P payload);

  /**
   * Adds an item with a key.
   *
   * @param key the item's key, unique within the batch
   * @param payload the item's payload, may be {@code null}
   * @return this builder
   */
  JobBatchBuilder<P, R> add(String key, @Nullable P payload);

  /**
   * Adds an item per payload, keyed by their positions.
   *
   * @param payloads the payloads
   * @return this builder
   */
  JobBatchBuilder<P, R> addAll(Iterable<? extends @Nullable P> payloads);

  /**
   * Adds an item per element, keyed by their positions.
   *
   * @param items the elements
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @return this builder
   */
  <T extends @Nullable Object> JobBatchBuilder<P, R> addAll(
      Iterable<T> items, Function<? super T, ? extends @Nullable P> payload);

  /**
   * Adds an item per element, with a key.
   *
   * @param items the elements
   * @param key maps an element to the item's key, unique within the batch
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @return this builder
   */
  <T extends @Nullable Object> JobBatchBuilder<P, R> addAll(
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable P> payload);

  /**
   * Starts the batch as a step of the parent, exactly once: the batch step, every item and its step
   * are written in one transaction. On later runs of the parent it returns the stored batch,
   * whatever the items are now.
   *
   * @return the batch, to wait for with {@link JobBatch#await()}
   * @throws IllegalArgumentException if keys are duplicated or invalid, or the batch exceeds the
   *     node's {@code maxJobBatchSize}
   * @throws IllegalStateException if called inside a step's handler, or the step name belongs to
   *     another type of step
   */
  JobBatch<R> schedule();

  /**
   * Starts the batch like {@link #schedule()}, with its items due at the given time.
   *
   * @param at the earliest time the items may run
   * @return the batch
   */
  JobBatch<R> schedule(Instant at);

  /**
   * Starts the batch like {@link #schedule()}, with its items due after the given delay.
   *
   * @param delay how long from now the items may run
   * @return the batch
   */
  JobBatch<R> schedule(Duration delay);
}
