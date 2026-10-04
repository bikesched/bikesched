package org.bikesched;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.bikesched.event.EventTimeoutException;
import org.bikesched.event.EventWait;
import org.bikesched.event.TimeoutHandler;
import org.bikesched.internal.Internals;
import org.bikesched.internal.Settings;
import org.bikesched.job.Job;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailure;
import org.bikesched.job.StepHandler;

/**
 * Builder for {@link Bikesched} instances, obtained via {@link Bikesched#builder()}. Not
 * thread-safe.
 */
public final class BikeschedBuilder {

  static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
  static final Duration DEFAULT_NODE_TIMEOUT = Duration.ofSeconds(30);
  static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  static final Duration DEFAULT_EVENT_RETENTION = Duration.ofDays(7);
  static final Duration DEFAULT_NODE_CLEAN_UP_INTERVAL = Duration.ofSeconds(15);
  static final Duration DEFAULT_EVENT_CLEAN_UP_INTERVAL = Duration.ofHours(1);
  static final int DEFAULT_MAX_CONCURRENT_JOBS = 10;

  private DataSource dataSource;
  private String nodeName;
  private int maxConcurrentJobs = DEFAULT_MAX_CONCURRENT_JOBS;
  private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
  private Duration nodeTimeout = DEFAULT_NODE_TIMEOUT;
  private Duration pollInterval = DEFAULT_POLL_INTERVAL;
  private boolean autoStartPolling = true;
  private JsonMapper jsonMapper = JsonMapper.rawJson();
  private Duration defaultJobTimeout;
  private Duration defaultEventTimeout;
  private RetryBackoff retryBackoff = RetryBackoff.none();
  private Duration eventRetention = DEFAULT_EVENT_RETENTION;
  private Duration nodeCleanUpInterval = DEFAULT_NODE_CLEAN_UP_INTERVAL;
  private Duration eventCleanUpInterval = DEFAULT_EVENT_CLEAN_UP_INTERVAL;

  BikeschedBuilder() {}

  /**
   * Sets the PostgreSQL data source bikesched stores its tables in (required).
   *
   * @param dataSource the data source
   * @return this builder
   */
  public BikeschedBuilder dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    return this;
  }

  /**
   * Sets the unique name this application instance registers as (optional, defaults to the host
   * name). Only one live node per name is allowed; the name of a dead node (no heartbeat for 30
   * seconds) is taken over.
   *
   * @param nodeName the node name
   * @return this builder
   */
  public BikeschedBuilder nodeName(String nodeName) {
    Objects.requireNonNull(nodeName, "nodeName");
    if (nodeName.isBlank()) {
      throw new IllegalArgumentException("node name must not be blank");
    }
    this.nodeName = nodeName;
    return this;
  }

  /**
   * Sets the maximum number of jobs this node executes at the same time (optional, default 10). The
   * node only claims jobs from the database while it has free capacity, so jobs it cannot start
   * right away stay available to other nodes.
   *
   * @param maxConcurrentJobs the limit, at least 1
   * @return this builder
   */
  public BikeschedBuilder maxConcurrentJobs(int maxConcurrentJobs) {
    if (maxConcurrentJobs < 1) {
      throw new IllegalArgumentException("maxConcurrentJobs must be >= 1: " + maxConcurrentJobs);
    }
    this.maxConcurrentJobs = maxConcurrentJobs;
    return this;
  }

  /**
   * Sets whether the node starts polling for jobs as soon as it is built (optional, default {@code
   * true}). With {@code false} jobs can be scheduled and events published, but the node is not
   * registered, executes no jobs and sends no heartbeats until {@link Bikesched#startPolling()} is
   * called, e.g. once all job handlers are registered, or never for an instance that only schedules
   * jobs. Until then its {@link Node#id() id} is empty.
   *
   * <p>With {@code false}, {@link #build()} also does not access the database: the schema is
   * migrated on first use, i.e. when polling starts or a job is scheduled or an event published, so
   * the instance can be built while the database is not available yet.
   *
   * @param autoStartPolling whether to start polling on {@link #build()}
   * @return this builder
   */
  public BikeschedBuilder autoStartPolling(boolean autoStartPolling) {
    this.autoStartPolling = autoStartPolling;
    return this;
  }

  /**
   * Sets the mapper converting job payloads and step results to and from JSON (optional, default
   * {@link JsonMapper#rawJson()}, which only supports strings already containing JSON).
   *
   * @param jsonMapper the mapper
   * @return this builder
   * @see JobBuilder#payload(Object)
   * @see Job#payload(Class)
   * @see Job#step(String, Class, StepHandler)
   */
  public BikeschedBuilder jsonMapper(JsonMapper jsonMapper) {
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    return this;
  }

  /**
   * Sets the maximum duration of a job attempt on this node for jobs scheduled without a timeout of
   * their own (optional, default: no timeout). See {@link JobBuilder#timeout(Duration)} for what
   * happens when an attempt exceeds it.
   *
   * @param timeout the timeout, positive
   * @return this builder
   */
  public BikeschedBuilder defaultJobTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    this.defaultJobTimeout = requirePositive(timeout, "defaultJobTimeout");
    return this;
  }

  /**
   * Sets how long this node delays the retry of a failed job attempt (optional, default {@link
   * RetryBackoff#none()}: retry as soon as possible). Applies whenever an attempt fails and
   * attempts remain, unless the handler sets the delay itself via {@link
   * JobFailure#retryAfter(Duration)}.
   *
   * @param retryBackoff the backoff, e.g. {@link RetryBackoff#exponential(Duration, Duration)}
   * @return this builder
   */
  public BikeschedBuilder retryBackoff(RetryBackoff retryBackoff) {
    this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
    return this;
  }

  /**
   * Sets the default timeout of waits for events (optional, default none: jobs wait until the event
   * is published). Applies to {@link Job#waitForEvent(String, String, String) waits} without a
   * {@link EventWait#timeout(Duration, TimeoutHandler) timeout} of their own, counted from when the
   * step first started waiting. Once it passed, the wait throws an {@link EventTimeoutException}
   * and the job fails without further attempts.
   *
   * @param timeout the default timeout, positive
   * @return this builder
   */
  public BikeschedBuilder defaultEventTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    this.defaultEventTimeout = requirePositive(timeout, "defaultEventTimeout");
    return this;
  }

  /**
   * Sets how long published events are kept (optional, default 7 days). A step that starts waiting
   * for an event receives the latest one published within this period, so events published before a
   * job waits for them are not lost. Older events are deleted by the nodes; steps that already
   * received an event keep its payload.
   *
   * @param retention how long events are kept, positive
   * @return this builder
   * @see Job#waitForEvent(String, String, String)
   */
  public BikeschedBuilder eventRetention(Duration retention) {
    Objects.requireNonNull(retention, "retention");
    this.eventRetention = requirePositive(retention, "eventRetention");
    return this;
  }

  /**
   * Sets how often this node cleans up after other nodes (optional, default 15 seconds): it
   * releases the running jobs of dead nodes (no heartbeat for 30 seconds) for retry and removes
   * dead nodes. Every polling node does this; shorter intervals recover the jobs of crashed nodes
   * sooner, at the cost of more queries. Also the retry delay of the check for missing occurrences
   * of the node's recurring jobs, which runs once when polling starts.
   *
   * @param interval the time between clean-ups, positive
   * @return this builder
   */
  public BikeschedBuilder nodeCleanUpInterval(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    this.nodeCleanUpInterval = requirePositive(interval, "nodeCleanUpInterval");
    return this;
  }

  /**
   * Sets how often this node deletes events older than the {@linkplain #eventRetention(Duration)
   * event retention} (optional, default 1 hour). Every polling node does this.
   *
   * @param interval the time between clean-ups, positive
   * @return this builder
   */
  public BikeschedBuilder eventCleanUpInterval(Duration interval) {
    Objects.requireNonNull(interval, "interval");
    this.eventCleanUpInterval = requirePositive(interval, "eventCleanUpInterval");
    return this;
  }

  /** Heartbeat interval; package-private so tests can speed it up. */
  BikeschedBuilder heartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    return this;
  }

  /**
   * Time without heartbeat after which a node is considered dead; package-private so tests can
   * speed it up.
   */
  BikeschedBuilder nodeTimeout(Duration nodeTimeout) {
    this.nodeTimeout = requirePositive(nodeTimeout, "nodeTimeout");
    return this;
  }

  /** Job poll interval; package-private so tests can speed it up. */
  BikeschedBuilder pollInterval(Duration pollInterval) {
    this.pollInterval = requirePositive(pollInterval, "pollInterval");
    return this;
  }

  private static Duration requirePositive(Duration duration, String name) {
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return duration;
  }

  /**
   * Creates the {@link Bikesched} instance: applies pending schema migrations and, unless disabled
   * via {@link #autoStartPolling(boolean)}, {@linkplain Bikesched#startPolling() starts polling},
   * which registers the node and starts its heartbeat. With polling disabled, this method does not
   * access the database: the migration runs on first use instead.
   *
   * @return the new instance
   * @throws IllegalStateException if no data source was set
   * @throws BikeschedException if polling is enabled and the migration or the node registration
   *     fails, e.g. because a live node with the same name is already registered
   */
  public Bikesched build() {
    if (dataSource == null) {
      throw new IllegalStateException("dataSource is required");
    }
    String name = nodeName != null ? nodeName : localHostName();
    var settings =
        new Settings(
            name,
            heartbeatInterval,
            nodeTimeout,
            pollInterval,
            maxConcurrentJobs,
            autoStartPolling,
            defaultJobTimeout,
            retryBackoff,
            eventRetention,
            defaultEventTimeout,
            nodeCleanUpInterval,
            eventCleanUpInterval);
    return Internals.create(settings, dataSource, jsonMapper);
  }

  private static String localHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      String fromEnv = System.getenv("HOSTNAME");
      if (fromEnv != null && !fromEnv.isBlank()) {
        return fromEnv;
      }
      throw new BikeschedException(
          "Cannot determine host name; set a node name via Bikesched.builder().nodeName(...)", e);
    }
  }
}
