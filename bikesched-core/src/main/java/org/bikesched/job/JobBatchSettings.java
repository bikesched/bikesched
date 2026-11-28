// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The settings of a job batch, shared by {@link JobBatchBuilder} and {@link MixedJobBatchBuilder}.
 * The job settings ({@link #priority(int)}, {@link #maxAttempts(int)}, {@link #timeout(Duration)},
 * {@link #reference(String)}) apply to every item, as for a child job started with {@link
 * Job#newJob(String)}.
 *
 * @param <B> the builder type
 */
public interface JobBatchSettings<B extends JobBatchSettings<B>> {

  /**
   * Sets the priority of every item, see {@link JobSettings#priority(int)}.
   *
   * @param priority higher priorities are claimed first (default 0)
   * @return this builder
   */
  B priority(int priority);

  /**
   * Sets the maximum number of attempts of every item, see {@link JobSettings#maxAttempts(int)}.
   *
   * @param maxAttempts at least 1 (default 3)
   * @return this builder
   * @throws IllegalArgumentException if {@code maxAttempts} is below 1
   */
  B maxAttempts(int maxAttempts);

  /**
   * Sets the timeout of each attempt of every item, see {@link JobSettings#timeout(Duration)}.
   *
   * @param timeout a positive duration
   * @return this builder
   */
  B timeout(Duration timeout);

  /**
   * Sets the reference of every item, see {@link JobSettings#reference(String)}; by default the
   * items inherit the parent's reference.
   *
   * @param reference the external entity the items are about, {@code null} for none
   * @return this builder
   */
  B reference(@Nullable String reference);

  /**
   * Limits the items active at once (scheduled, running, waiting or retrying) to {@code items},
   * across all nodes. The others are {@link JobState#HELD HELD} and admitted, in the order they
   * were added, as active items end. By default all items are due right away.
   *
   * @param items at least 1
   * @return this builder
   * @throws IllegalArgumentException if {@code items} is below 1
   */
  B parallelism(int items);

  /**
   * Ends the batch at the first item that does not succeed (failed, cancelled or its compensation
   * failed): the parent's {@link JobBatch#await()} throws (and {@link JobBatch#awaitResult()}
   * returns) right away, held items are cancelled and the other unfinished items get a cancellation
   * request. By default a batch ends when all its items have ended.
   *
   * @return this builder
   */
  B failFast();

  /**
   * Sets the compensation of each started item: when the parent is cancelled or fails permanently,
   * it runs once per item that was admitted, in the reverse order of the items, receiving the
   * item's key and job id (e.g. to cancel the item or undo its work). Items that were still held
   * never ran and are not compensated.
   *
   * @param compensation the compensation
   * @return this builder
   */
  B compensateWith(CompensationHandler<? super JobBatchItem> compensation);
}
