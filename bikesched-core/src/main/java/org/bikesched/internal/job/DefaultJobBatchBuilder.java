// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import org.bikesched.JsonMapper;
import org.bikesched.job.JobBatch;
import org.bikesched.job.JobBatchBuilder;
import org.jspecify.annotations.Nullable;

/** The {@link JobBatchBuilder} implementation: a batch of one job. */
final class DefaultJobBatchBuilder<P extends @Nullable Object, R extends @Nullable Object>
    extends BaseJobBatchBuilder<JobBatchBuilder<P, R>> implements JobBatchBuilder<P, R> {

  private final String jobName;
  private final Class<?> resultType;

  DefaultJobBatchBuilder(
      DefaultJob parent,
      String stepName,
      String jobName,
      Class<?> resultType,
      JsonMapper jsonMapper) {
    super(parent, stepName, jsonMapper);
    this.jobName = jobName;
    this.resultType = resultType;
  }

  @Override
  JobBatchBuilder<P, R> self() {
    return this;
  }

  @Override
  public JobBatchBuilder<P, R> add(@Nullable P payload) {
    addItem(jobName, null, payload, resultType);
    return this;
  }

  @Override
  public JobBatchBuilder<P, R> add(String key, @Nullable P payload) {
    addItem(jobName, key(key), payload, resultType);
    return this;
  }

  @Override
  public JobBatchBuilder<P, R> addAll(Iterable<? extends @Nullable P> payloads) {
    Objects.requireNonNull(payloads, "payloads");
    for (P payload : payloads) {
      addItem(jobName, null, payload, resultType);
    }
    return this;
  }

  @Override
  public <T extends @Nullable Object> JobBatchBuilder<P, R> addAll(
      Iterable<T> items, Function<? super T, ? extends @Nullable P> payload) {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(jobName, null, payload.apply(item), resultType);
    }
    return this;
  }

  @Override
  public <T extends @Nullable Object> JobBatchBuilder<P, R> addAll(
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable P> payload) {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(jobName, key(key.apply(item)), payload.apply(item), resultType);
    }
    return this;
  }

  @Override
  public JobBatch<R> schedule() {
    return start(Instant.now());
  }

  @Override
  public JobBatch<R> schedule(Instant at) {
    return start(at);
  }

  @Override
  public JobBatch<R> schedule(Duration delay) {
    return start(delay);
  }
}
