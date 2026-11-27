// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bikesched.JsonMapper;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.CompensationHandler;
import org.bikesched.job.JobBatchItem;
import org.bikesched.job.JobBatchSettings;
import org.jspecify.annotations.Nullable;

/**
 * The shared settings and items of the job batch builders ({@link DefaultJobBatchBuilder}, {@link
 * DefaultMixedJobBatchBuilder}); the batch is started by {@link DefaultJob#startJobBatch}.
 *
 * @param <B> the builder interface
 */
abstract class BaseJobBatchBuilder<B extends JobBatchSettings<B>> implements JobBatchSettings<B> {

  /**
   * An item as added.
   *
   * @param jobName its job name
   * @param key its key, {@code null} for its position
   * @param payload its payload, mapped to JSON only when the batch starts; {@code null} if none
   * @param resultType the type its result is read as
   */
  record Item(
      String jobName, @Nullable String key, @Nullable Object payload, Class<?> resultType) {}

  final DefaultJob parent;
  final String stepName;
  final JsonMapper jsonMapper;
  final List<Item> items = new ArrayList<>();
  int priority = BaseJobBuilder.DEFAULT_PRIORITY;
  int maxAttempts = BaseJobBuilder.DEFAULT_MAX_ATTEMPTS;
  @Nullable Duration timeout;
  @Nullable String reference;
  boolean referenceSet;
  @Nullable Integer parallelism;
  boolean failFast;
  @Nullable CompensationHandler<? super JobBatchItem> compensation;

  BaseJobBatchBuilder(DefaultJob parent, String stepName, JsonMapper jsonMapper) {
    this.parent = parent;
    this.stepName = stepName;
    this.jsonMapper = jsonMapper;
  }

  /** This builder as its interface type. */
  abstract B self();

  /** The type a result is read as for a stored item this run did not add ({@code Object}: any). */
  abstract Class<?> defaultResultType();

  /** Adds an item; its payload is mapped once the batch starts, never on later runs. */
  final void addItem(
      String jobName, @Nullable String key, @Nullable Object payload, Class<?> resultType) {
    if (key != null) {
      ValidationUtil.requireName(key, "batch key");
    }
    items.add(new Item(jobName, key, payload, resultType));
  }

  /** Checks the key given for an item: not {@code null}, a valid name. */
  static String key(@Nullable String key) {
    return ValidationUtil.requireName(Objects.requireNonNull(key, "batch key"), "batch key");
  }

  @Override
  public B priority(int priority) {
    this.priority = priority;
    return self();
  }

  @Override
  public B maxAttempts(int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
    }
    this.maxAttempts = maxAttempts;
    return self();
  }

  @Override
  public B timeout(Duration timeout) {
    ValidationUtil.requirePositive(timeout, "timeout");
    this.timeout = timeout;
    return self();
  }

  @Override
  public B reference(@Nullable String reference) {
    this.reference = reference == null ? null : ValidationUtil.requireName(reference, "reference");
    this.referenceSet = true;
    return self();
  }

  @Override
  public B parallelism(int items) {
    if (items < 1) {
      throw new IllegalArgumentException("parallelism must be >= 1: " + items);
    }
    this.parallelism = items;
    return self();
  }

  @Override
  public B failFast() {
    this.failFast = true;
    return self();
  }

  @Override
  public B compensateWith(CompensationHandler<? super JobBatchItem> compensation) {
    this.compensation = Objects.requireNonNull(compensation, "compensation");
    return self();
  }

  /** Starts the batch with its items due at the given time. */
  final <R extends @Nullable Object> DefaultJobBatch<R> start(Instant at) {
    Objects.requireNonNull(at, "at");
    return parent.startJobBatch(this, at);
  }

  /** Starts the batch with its items due after the given delay. */
  final <R extends @Nullable Object> DefaultJobBatch<R> start(Duration delay) {
    return start(BaseJobBuilder.after(delay));
  }
}
