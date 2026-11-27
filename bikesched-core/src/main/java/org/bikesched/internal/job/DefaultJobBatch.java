// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bikesched.internal.database.BatchStore.ItemOutcome;
import org.bikesched.internal.database.BatchStore.ItemRef;
import org.bikesched.internal.database.StepRef;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobBatch;
import org.bikesched.job.JobBatchResult;
import org.jspecify.annotations.Nullable;

/** The {@link JobBatch} implementation: a started batch of its parent {@link DefaultJob}. */
final class DefaultJobBatch<R extends @Nullable Object> implements JobBatch<R> {

  private final DefaultJob parent;
  private final StepRef step;
  private final Map<String, UUID> jobIds;
  private final Map<String, Class<?>> resultTypes;

  /**
   * @param parent the job that started the batch
   * @param step the batch step
   * @param items its items, in order
   * @param resultTypes the type of each item's result, by key; {@code Object} for others
   */
  DefaultJobBatch(
      DefaultJob parent, StepRef step, List<ItemRef> items, Map<String, Class<?>> resultTypes) {
    this.parent = parent;
    this.step = step;
    var ids = new LinkedHashMap<String, UUID>();
    for (ItemRef item : items) {
      ids.put(item.key(), item.jobId());
    }
    this.jobIds = Collections.unmodifiableMap(ids);
    this.resultTypes = Map.copyOf(resultTypes);
  }

  @Override
  public String stepName() {
    return step.name();
  }

  /** The batch step. */
  StepRef step() {
    return step;
  }

  @Override
  public Map<String, UUID> jobIds() {
    return jobIds;
  }

  @Override
  public JobBatchResult<R> await() {
    return parent.awaitBatch(this, null);
  }

  @Override
  public JobBatchResult<R> await(Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    return parent.awaitBatch(this, timeout);
  }

  /** The result of the ended batch with the given items. */
  JobBatchResult<R> result(List<ItemOutcome> items) {
    return new DefaultJobBatchResult<>(
        step.name(), items, key -> resultTypes.getOrDefault(key, Object.class), parent);
  }

  @Override
  public String toString() {
    return "JobBatch[stepName=" + step.name() + ", items=" + jobIds.size() + "]";
  }
}
