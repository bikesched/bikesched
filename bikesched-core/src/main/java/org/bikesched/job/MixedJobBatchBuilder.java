// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Builds a batch of child jobs of different jobs, each item naming its job, see {@link
 * Job#newJobBatch(String)}. Keys work as in {@link JobBatchBuilder}; the results are read typed by
 * their job with {@link JobBatchResult#get(String, ResultJob)}.
 */
public interface MixedJobBatchBuilder extends JobBatchSettings<MixedJobBatchBuilder> {

  /**
   * Adds an item of a typed job, keyed by its position.
   *
   * @param job the item's job
   * @param payload its payload, may be {@code null}
   * @param <P> the payload type
   * @return this builder
   */
  <P extends @Nullable Object> MixedJobBatchBuilder add(TypedJob<P> job, @Nullable P payload);

  /**
   * Adds an item of a typed job, with a key.
   *
   * @param job the item's job
   * @param key the item's key, unique within the batch
   * @param payload its payload, may be {@code null}
   * @param <P> the payload type
   * @return this builder
   */
  <P extends @Nullable Object> MixedJobBatchBuilder add(
      TypedJob<P> job, String key, @Nullable P payload);

  /**
   * Adds an item per element of a typed job, keyed by their positions.
   *
   * @param job the items' job
   * @param items the elements
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @param <P> the payload type
   * @return this builder
   */
  <T extends @Nullable Object, P extends @Nullable Object> MixedJobBatchBuilder addAll(
      TypedJob<P> job, Iterable<T> items, Function<? super T, ? extends @Nullable P> payload);

  /**
   * Adds an item per element of a typed job, with a key.
   *
   * @param job the items' job
   * @param items the elements
   * @param key maps an element to the item's key, unique within the batch
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @param <P> the payload type
   * @return this builder
   */
  <T extends @Nullable Object, P extends @Nullable Object> MixedJobBatchBuilder addAll(
      TypedJob<P> job,
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable P> payload);

  /**
   * Adds an item of a job given by its name, keyed by its position; the payload is mapped as for
   * {@link Job#newJob(String)}.
   *
   * @param jobName the item's job name
   * @param payload its payload, may be {@code null}
   * @return this builder
   */
  MixedJobBatchBuilder add(String jobName, @Nullable Object payload);

  /**
   * Adds an item of a job given by its name, with a key.
   *
   * @param jobName the item's job name
   * @param key the item's key, unique within the batch
   * @param payload its payload, may be {@code null}
   * @return this builder
   */
  MixedJobBatchBuilder add(String jobName, String key, @Nullable Object payload);

  /**
   * Adds an item per element of a job given by its name, keyed by their positions.
   *
   * @param jobName the items' job name
   * @param items the elements
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @return this builder
   */
  <T extends @Nullable Object> MixedJobBatchBuilder addAll(
      String jobName, Iterable<T> items, Function<? super T, ? extends @Nullable Object> payload);

  /**
   * Adds an item per element of a job given by its name, with a key.
   *
   * @param jobName the items' job name
   * @param items the elements
   * @param key maps an element to the item's key, unique within the batch
   * @param payload maps an element to the item's payload
   * @param <T> the element type
   * @return this builder
   */
  <T extends @Nullable Object> MixedJobBatchBuilder addAll(
      String jobName,
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable Object> payload);

  /**
   * Starts the batch as a step of the parent, see {@link JobBatchBuilder#schedule()}.
   *
   * @return the batch
   */
  JobBatch<Object> schedule();

  /**
   * Starts the batch with its items due at the given time, see {@link JobBatchBuilder#schedule()}.
   *
   * @param at the earliest time the items may run
   * @return the batch
   */
  JobBatch<Object> schedule(Instant at);

  /**
   * Starts the batch with its items due after the given delay, see {@link
   * JobBatchBuilder#schedule()}.
   *
   * @param delay how long from now the items may run
   * @return the batch
   */
  JobBatch<Object> schedule(Duration delay);
}
