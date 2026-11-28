// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bikesched.internal.database.BatchStore.ItemOutcome;
import org.bikesched.job.JobBatchResult;
import org.bikesched.job.JobFailedException;
import org.bikesched.job.JobResult;
import org.bikesched.job.JobState;
import org.bikesched.job.ResultJob;
import org.jspecify.annotations.Nullable;

/**
 * The {@link JobBatchResult} implementation: the stored outcomes of a batch's items, their results
 * mapped from JSON per item when read.
 */
abstract class DefaultJobBatchResult<R extends @Nullable Object> {

  private final String stepName;
  private final Map<String, ItemOutcome> items;
  private final Function<String, Class<?>> resultTypes;
  private final DefaultJob reader;
  private final Map<String, JobResult<R>> mapped = new ConcurrentHashMap<>();

  static <R extends @Nullable Object> JobBatchResult<R> of(
      String stepName,
      List<ItemOutcome> items,
      Function<String, Class<?>> resultTypes,
      DefaultJob reader) {
    long succeededCount = items.stream().filter(DefaultJobBatchResult::succeeded).count();
    if (succeededCount == items.size()) {
      return new Succeeded<>(stepName, items, resultTypes, reader);
    }
    if (succeededCount > 0) {
      return new Incomplete<>(stepName, items, resultTypes, reader);
    }
    return new Failed<>(stepName, items, resultTypes, reader);
  }

  private DefaultJobBatchResult(
      String stepName,
      List<ItemOutcome> items,
      Function<String, Class<?>> resultTypes,
      DefaultJob reader) {
    this.stepName = stepName;
    var byKey = new LinkedHashMap<String, ItemOutcome>();
    for (ItemOutcome item : items) {
      byKey.put(item.item().key(), item);
    }
    this.items = Collections.unmodifiableMap(byKey);
    this.resultTypes = resultTypes;
    this.reader = reader;
  }

  static final class Succeeded<R extends @Nullable Object> extends DefaultJobBatchResult<R>
      implements JobBatchResult.Succeeded<R> {

    Succeeded(
        String stepName,
        List<ItemOutcome> items,
        Function<String, Class<?>> resultTypes,
        DefaultJob reader) {
      super(stepName, items, resultTypes, reader);
    }

    @Override
    public boolean isAllSucceeded() {
      return true;
    }

    @Override
    public List<@Nullable R> orElseThrow() {
      return allResults();
    }

    @Override
    public List<@Nullable R> results() {
      return allResults();
    }

    @Override
    String variant() {
      return "Succeeded";
    }
  }

  static sealed class Failed<R extends @Nullable Object> extends DefaultJobBatchResult<R>
      implements JobBatchResult.Failed<R> permits Incomplete {

    Failed(
        String stepName,
        List<ItemOutcome> items,
        Function<String, Class<?>> resultTypes,
        DefaultJob reader) {
      super(stepName, items, resultTypes, reader);
    }

    @Override
    public boolean isAllSucceeded() {
      return false;
    }

    @Override
    String variant() {
      return "Failed";
    }
  }

  static final class Incomplete<R extends @Nullable Object> extends Failed<R>
      implements JobBatchResult.Failed.Incomplete<R> {

    Incomplete(
        String stepName,
        List<ItemOutcome> items,
        Function<String, Class<?>> resultTypes,
        DefaultJob reader) {
      super(stepName, items, resultTypes, reader);
    }

    @Override
    String variant() {
      return "Failed.Incomplete";
    }
  }

  abstract String variant();

  private static boolean succeeded(ItemOutcome item) {
    return item.childState() == JobState.SUCCEEDED;
  }

  public List<@Nullable R> orElseThrow() {
    List<String> unsucceeded =
        items.values().stream()
            .filter(item -> !succeeded(item))
            .map(item -> item.item().key())
            .toList();
    if (!unsucceeded.isEmpty()) {
      JobResult<R> first = get(unsucceeded.getFirst());
      throw new JobFailedException(
          (unsucceeded.size() == 1 ? "Item " : "Items ")
              + String.join(", ", unsucceeded.stream().map(key -> "'" + key + "'").toList())
              + " of job batch '"
              + stepName
              + "' did not succeed; "
              + new JobFailedException(first).getMessage(),
          first);
    }
    return allResults();
  }

  List<@Nullable R> allResults() {
    List<@Nullable R> results = new ArrayList<>(items.size());
    for (String key : items.keySet()) {
      results.add(get(key).orElse(null));
    }
    return Collections.unmodifiableList(results);
  }

  public Map<String, @Nullable R> succeeded() {
    var results = new LinkedHashMap<String, @Nullable R>();
    for (ItemOutcome item : items.values()) {
      if (succeeded(item)) {
        String key = item.item().key();
        results.put(key, get(key).orElse(null));
      }
    }
    return Collections.unmodifiableMap(results);
  }

  public Map<String, JobResult<R>> unsucceeded() {
    var results = new LinkedHashMap<String, JobResult<R>>();
    for (ItemOutcome item : items.values()) {
      if (!succeeded(item)) {
        String key = item.item().key();
        results.put(key, get(key));
      }
    }
    return Collections.unmodifiableMap(results);
  }

  public Map<String, JobResult<R>> all() {
    var results = new LinkedHashMap<String, JobResult<R>>();
    for (String key : items.keySet()) {
      results.put(key, get(key));
    }
    return Collections.unmodifiableMap(results);
  }

  @SuppressWarnings("unchecked")
  public JobResult<R> get(String key) {
    ItemOutcome item = item(key);
    return mapped.computeIfAbsent(
        key, ignored -> (JobResult<R>) outcome(item, resultTypes.apply(key)));
  }

  public <T extends @Nullable Object> JobResult<T> get(String key, ResultJob<?, T> job) {
    Objects.requireNonNull(job, "job");
    ItemOutcome item = item(key);
    if (!item.item().jobName().equals(job.name())) {
      throw new IllegalArgumentException(
          "Item '"
              + key
              + "' of job batch '"
              + stepName
              + "' is a job named "
              + item.item().jobName()
              + ", not "
              + job.name());
    }
    return outcome(item, job.resultType());
  }

  public <T extends @Nullable Object> JobResult<T> get(String key, Class<T> type) {
    Objects.requireNonNull(type, "type");
    return outcome(item(key), type);
  }

  private ItemOutcome item(String key) {
    Objects.requireNonNull(key, "key");
    ItemOutcome item = items.get(key);
    if (item == null) {
      throw new IllegalArgumentException(
          "Job batch '" + stepName + "' has no item '" + key + "', only " + items.keySet());
    }
    return item;
  }

  /** A succeeded item's result read as the given type, naming the item if that fails. */
  private <T extends @Nullable Object> @Nullable T result(ItemOutcome item, Class<T> type) {
    if (type == Void.class) {
      return null;
    }
    try {
      return reader.reader(type).apply(item.result());
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Reading the result of item '"
              + item.item().key()
              + "' of job batch '"
              + stepName
              + "' as "
              + type.getName()
              + " failed",
          e);
    }
  }

  /** The outcome of an item with its result read as the given type ({@code Void}: none). */
  private <T extends @Nullable Object> JobResult<T> outcome(ItemOutcome item, Class<T> type) {
    UUID jobId = item.item().jobId();
    JobState childState = item.childState();
    if (childState == null) {
      return switch (item.state()) {
        case TIMED_OUT -> new JobResult.TimedOut<>(jobId);
        default -> new JobResult.Cancelled<>(jobId, item.error());
      };
    }
    return switch (childState) {
      case SUCCEEDED -> new JobResult.Succeeded<>(jobId, result(item, type));
      case FAILED -> new JobResult.Failed<>(jobId, Objects.requireNonNullElse(item.error(), ""));
      case CANCELLED -> new JobResult.Cancelled<>(jobId, item.error());
      case COMPENSATION_FAILED ->
          new JobResult.CompensationFailed<>(jobId, Objects.requireNonNullElse(item.error(), ""));
      case SCHEDULED, RUNNING, WAITING, COMPENSATING, FAILING, HELD ->
          throw new IllegalStateException(
              "Unknown state " + childState + " of item " + jobId + " of job batch " + stepName);
    };
  }

  @Override
  public String toString() {
    return "JobBatchResult."
        + variant()
        + "[stepName="
        + stepName
        + ", items="
        + items.size()
        + "]";
  }
}
