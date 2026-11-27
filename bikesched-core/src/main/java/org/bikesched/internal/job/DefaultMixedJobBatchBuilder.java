// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import org.bikesched.JsonMapper;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobBatch;
import org.bikesched.job.MixedJobBatchBuilder;
import org.bikesched.job.ResultJob;
import org.bikesched.job.TypedJob;
import org.jspecify.annotations.Nullable;

/** The {@link MixedJobBatchBuilder} implementation: each item names its job. */
final class DefaultMixedJobBatchBuilder extends BaseJobBatchBuilder<MixedJobBatchBuilder>
    implements MixedJobBatchBuilder {

  private final JobScheduler scheduler;

  DefaultMixedJobBatchBuilder(
      DefaultJob parent, String stepName, JsonMapper jsonMapper, JobScheduler scheduler) {
    super(parent, stepName, jsonMapper);
    this.scheduler = scheduler;
  }

  @Override
  MixedJobBatchBuilder self() {
    return this;
  }

  /** The job name of a typed job, remembering its payload type. */
  private String name(TypedJob<?> job) {
    Objects.requireNonNull(job, "job");
    scheduler.requirePayloadType(job);
    return job.name();
  }

  private static Class<?> resultType(TypedJob<?> job) {
    return job instanceof ResultJob<?, ?> resultJob ? resultJob.resultType() : Object.class;
  }

  private static String name(String jobName) {
    return ValidationUtil.requireName(jobName, "job name");
  }

  @Override
  public <P extends @Nullable Object> MixedJobBatchBuilder add(
      TypedJob<P> job, @Nullable P payload) {
    addItem(name(job), null, payload, resultType(job));
    return this;
  }

  @Override
  public <P extends @Nullable Object> MixedJobBatchBuilder add(
      TypedJob<P> job, String key, @Nullable P payload) {
    addItem(name(job), key(key), payload, resultType(job));
    return this;
  }

  @Override
  public <T extends @Nullable Object, P extends @Nullable Object> MixedJobBatchBuilder addAll(
      TypedJob<P> job, Iterable<T> items, Function<? super T, ? extends @Nullable P> payload) {
    String jobName = name(job);
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(jobName, null, payload.apply(item), resultType(job));
    }
    return this;
  }

  @Override
  public <T extends @Nullable Object, P extends @Nullable Object> MixedJobBatchBuilder addAll(
      TypedJob<P> job,
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable P> payload) {
    String jobName = name(job);
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(jobName, key(key.apply(item)), payload.apply(item), resultType(job));
    }
    return this;
  }

  @Override
  public MixedJobBatchBuilder add(String jobName, @Nullable Object payload) {
    addItem(name(jobName), null, payload, Object.class);
    return this;
  }

  @Override
  public MixedJobBatchBuilder add(String jobName, String key, @Nullable Object payload) {
    addItem(name(jobName), key(key), payload, Object.class);
    return this;
  }

  @Override
  public <T extends @Nullable Object> MixedJobBatchBuilder addAll(
      String jobName, Iterable<T> items, Function<? super T, ? extends @Nullable Object> payload) {
    String name = name(jobName);
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(name, null, payload.apply(item), Object.class);
    }
    return this;
  }

  @Override
  public <T extends @Nullable Object> MixedJobBatchBuilder addAll(
      String jobName,
      Iterable<T> items,
      Function<? super T, String> key,
      Function<? super T, ? extends @Nullable Object> payload) {
    String name = name(jobName);
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
    for (T item : items) {
      addItem(name, key(key.apply(item)), payload.apply(item), Object.class);
    }
    return this;
  }

  @Override
  public JobBatch<Object> schedule() {
    return start(Instant.now());
  }

  @Override
  public JobBatch<Object> schedule(Instant at) {
    return start(at);
  }

  @Override
  public JobBatch<Object> schedule(Duration delay) {
    return start(delay);
  }
}
