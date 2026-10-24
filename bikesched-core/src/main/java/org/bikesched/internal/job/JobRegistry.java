// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.TypedJob;
import org.bikesched.job.TypedJobFailureHandler;
import org.jspecify.annotations.Nullable;

/**
 * The job names a node knows: their handlers, its recurring jobs and the payload classes of typed
 * job names. Used when scheduling (with or without polling) and by the poller.
 */
public final class JobRegistry {

  private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
  private final Map<String, JobFailureHandler> failureHandlers = new ConcurrentHashMap<>();
  private final Map<String, RecurringJob> recurringJobs = new ConcurrentHashMap<>();

  /** Payload classes of the job names used with a {@link TypedJob} on this node, by name. */
  private final Map<String, Class<?>> payloadTypes = new ConcurrentHashMap<>();

  /** Runs after a handler of a new name was registered; set by the poller. */
  private volatile Runnable onNewHandler = () -> {};

  /**
   * Sets what runs after a handler of a new name was registered (the poller then looks for its
   * jobs).
   *
   * @param onNewHandler runs on the registering thread; must not throw
   */
  public void onNewHandler(Runnable onNewHandler) {
    this.onNewHandler = Objects.requireNonNull(onNewHandler, "onNewHandler");
  }

  /**
   * Registers the handler of a job name unless it has one.
   *
   * @param name the job name
   * @param handler its handler
   * @return {@code false} if the name already had a handler (which is kept)
   */
  public boolean register(String name, JobHandler handler) {
    Objects.requireNonNull(handler, "handler");
    if (handlers.putIfAbsent(name, handler) != null) {
      return false;
    }
    onNewHandler.run();
    return true;
  }

  /**
   * The handler of a job name.
   *
   * @param name the job name
   * @return the handler, {@code null} if none is registered
   */
  public @Nullable JobHandler handler(String name) {
    return handlers.get(name);
  }

  /**
   * Registers the failure handler of a job name unless it has one.
   *
   * @param name the job name
   * @param handler its failure handler
   * @return {@code false} if the name already had a failure handler (which is kept)
   */
  public boolean registerFailureHandler(String name, JobFailureHandler handler) {
    Objects.requireNonNull(handler, "handler");
    return failureHandlers.putIfAbsent(name, handler) == null;
  }

  /**
   * Adapts a failure handler receiving the mapped payload.
   *
   * @param handler the typed failure handler
   * @param payloadType the payload class
   * @param <T> the payload type
   * @return the failure handler
   */
  public static <T extends @Nullable Object> JobFailureHandler typed(
      TypedJobFailureHandler<? super T> handler, Class<T> payloadType) {
    Objects.requireNonNull(handler, "handler");
    return failed -> handler.handle(failed, failed.payload(payloadType));
  }

  /**
   * The failure handler of a job name.
   *
   * @param name the job name
   * @return the failure handler, {@code null} if none is registered
   */
  public @Nullable JobFailureHandler failureHandler(String name) {
    return failureHandlers.get(name);
  }

  /**
   * The job names with a handler.
   *
   * @return a live view
   */
  public Set<String> names() {
    return handlers.keySet();
  }

  /**
   * Checks that a job name has a handler.
   *
   * @param name the job name
   * @throws IllegalArgumentException if it has none
   */
  public void requireHandler(String name) {
    if (!handlers.containsKey(name)) {
      throw new IllegalArgumentException(
          "Unknown job name '"
              + name
              + "': no handler registered (known names: "
              + new TreeSet<>(handlers.keySet())
              + ")");
    }
  }

  /**
   * Remembers the payload class of a typed job name; rejects a second payload class for the same
   * name on this node, which would make producers and handlers disagree on the payload.
   *
   * @param typedJob the job name and its payload class
   * @throws IllegalArgumentException if the name is already used with another payload class
   */
  public void requirePayloadType(TypedJob<?> typedJob) {
    Objects.requireNonNull(typedJob, "typedJob");
    Class<?> known = payloadTypes.putIfAbsent(typedJob.name(), typedJob.payloadType());
    if (known != null && known != typedJob.payloadType()) {
      throw new IllegalArgumentException(
          "Job name '"
              + typedJob.name()
              + "' is already used with payload type "
              + known.getName()
              + ", not "
              + typedJob.payloadType().getName());
    }
  }

  /**
   * Adds (or replaces) a recurring job of this node.
   *
   * @param job the recurring job
   */
  public void addRecurringJob(RecurringJob job) {
    recurringJobs.put(job.name(), job);
  }

  /**
   * Removes a recurring job from this node.
   *
   * @param name its job name
   * @return whether this node had it
   */
  public boolean removeRecurringJob(String name) {
    return recurringJobs.remove(name) != null;
  }

  /**
   * A recurring job of this node.
   *
   * @param name its job name
   * @return the recurring job, {@code null} if this node has none of that name
   */
  public @Nullable RecurringJob recurringJob(String name) {
    return recurringJobs.get(name);
  }

  /**
   * The recurring jobs of this node.
   *
   * @return a live view
   */
  public Collection<RecurringJob> recurringJobs() {
    return recurringJobs.values();
  }
}
