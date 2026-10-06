package org.bikesched.internal.job;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobType;

/**
 * The job types a node knows: their handlers, its recurring jobs and the payload classes of typed
 * job types. Used when scheduling (with or without polling) and by the poller.
 */
public final class JobRegistry {

  private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
  private final Map<String, RecurringJob> recurringJobs = new ConcurrentHashMap<>();

  /** Payload classes of the job types used with a {@link JobType} on this node, by name. */
  private final Map<String, Class<?>> payloadTypes = new ConcurrentHashMap<>();

  /**
   * Registers the handler of a job type unless it has one.
   *
   * @param type the job type
   * @param handler its handler
   * @return {@code false} if the type already had a handler (which is kept)
   */
  public boolean register(String type, JobHandler handler) {
    Objects.requireNonNull(handler, "handler");
    return handlers.putIfAbsent(type, handler) == null;
  }

  /**
   * The handler of a job type.
   *
   * @param type the job type
   * @return the handler, {@code null} if none is registered
   */
  public JobHandler handler(String type) {
    return handlers.get(type);
  }

  /**
   * The job types with a handler.
   *
   * @return a live view
   */
  public Set<String> types() {
    return handlers.keySet();
  }

  /**
   * Checks that a job type has a handler.
   *
   * @param type the job type
   * @throws IllegalArgumentException if it has none
   */
  public void requireHandler(String type) {
    if (!handlers.containsKey(type)) {
      throw new IllegalArgumentException(
          "Unknown job type '"
              + type
              + "': no handler registered (known types: "
              + new TreeSet<>(handlers.keySet())
              + ")");
    }
  }

  /**
   * Remembers the payload class of a typed job type; rejects a second payload class for the same
   * name on this node, which would make producers and handlers disagree on the payload.
   *
   * @param type the typed job type
   * @throws IllegalArgumentException if the name is already used with another payload class
   */
  public void requirePayloadType(JobType<?> type) {
    Objects.requireNonNull(type, "type");
    Class<?> known = payloadTypes.putIfAbsent(type.name(), type.payloadType());
    if (known != null && known != type.payloadType()) {
      throw new IllegalArgumentException(
          "Job type '"
              + type.name()
              + "' is already used with payload type "
              + known.getName()
              + ", not "
              + type.payloadType().getName());
    }
  }

  /**
   * Adds (or replaces) a recurring job of this node.
   *
   * @param job the recurring job
   */
  public void addRecurringJob(RecurringJob job) {
    recurringJobs.put(job.type(), job);
  }

  /**
   * Removes a recurring job from this node.
   *
   * @param type its job type
   * @return whether this node had it
   */
  public boolean removeRecurringJob(String type) {
    return recurringJobs.remove(type) != null;
  }

  /**
   * A recurring job of this node.
   *
   * @param type its job type
   * @return the recurring job, {@code null} if this node has none of that type
   */
  public RecurringJob recurringJob(String type) {
    return recurringJobs.get(type);
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
