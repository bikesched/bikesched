// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.bikesched.event.EventTimeoutException;
import org.bikesched.event.EventWait;
import org.bikesched.event.listener.EventListener;
import org.bikesched.internal.Internals;
import org.bikesched.internal.Settings;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.Job;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailure;
import org.bikesched.job.RecurrenceParser;
import org.bikesched.job.StepHandler;
import org.bikesched.job.listener.JobListener;
import org.jspecify.annotations.Nullable;

/**
 * Builder for {@link Bikesched} instances, obtained via {@link Bikesched#builder()}. Not
 * thread-safe.
 */
public final class BikeschedBuilder {

  static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);
  static final Duration DEFAULT_NODE_TIMEOUT = Duration.ofSeconds(30);
  static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);
  static final Duration DEFAULT_NOTIFY_INTERVAL = Duration.ofMillis(100);
  static final Duration DEFAULT_EVENT_RETENTION = Duration.ofDays(7);
  static final Duration DEFAULT_NODE_CLEAN_UP_INTERVAL = Duration.ofSeconds(15);
  static final Duration DEFAULT_EVENT_CLEAN_UP_INTERVAL = Duration.ofHours(1);
  static final Duration DEFAULT_JOB_HISTORY_CLEAN_UP_INTERVAL = Duration.ofHours(1);
  static final Duration DEFAULT_JOB_RETENTION = Duration.ofDays(7);
  static final Duration DEFAULT_JOB_CLEAN_UP_INTERVAL = Duration.ofHours(1);
  static final int DEFAULT_MAX_CONCURRENT_JOBS = 10;
  static final int DEFAULT_FAILURE_HANDLER_ATTEMPTS = 3;

  private @Nullable DataSource dataSource;
  private @Nullable String nodeName;
  private int maxConcurrentJobs = DEFAULT_MAX_CONCURRENT_JOBS;
  private Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
  private Duration nodeTimeout = DEFAULT_NODE_TIMEOUT;
  private Duration pollInterval = DEFAULT_POLL_INTERVAL;
  private boolean listenForDueJobs = true;
  private Duration notifyInterval = DEFAULT_NOTIFY_INTERVAL;
  private boolean autoStartPolling = true;
  private boolean migrateSchema = true;
  private @Nullable JsonMapper jsonMapper; // null: the default, accepting only JSON strings
  private @Nullable Duration defaultJobTimeout;
  private @Nullable Duration defaultEventTimeout;
  private RetryBackoff retryBackoff = RetryBackoff.none();
  private int failureHandlerAttempts = DEFAULT_FAILURE_HANDLER_ATTEMPTS;
  private Duration eventRetention = DEFAULT_EVENT_RETENTION;
  private Duration nodeCleanUpInterval = DEFAULT_NODE_CLEAN_UP_INTERVAL;
  private Duration eventCleanUpInterval = DEFAULT_EVENT_CLEAN_UP_INTERVAL;
  private @Nullable Duration jobHistoryRetention; // null: no job history
  private Duration jobHistoryCleanUpInterval = DEFAULT_JOB_HISTORY_CLEAN_UP_INTERVAL;
  private Duration jobRetention = DEFAULT_JOB_RETENTION;
  private Duration jobCleanUpInterval = DEFAULT_JOB_CLEAN_UP_INTERVAL;
  private @Nullable Duration statementTimeout; // null: left to the data source
  private @Nullable Duration hardTimeoutGrace; // null: no hard timeout
  private JobListener jobListener = new JobListener() {};
  private EventListener eventListener = new EventListener() {};
  private List<RecurrenceParser> recurrenceParsers = List.of(RecurrenceParser.isoDuration());

  BikeschedBuilder() {}

  /**
   * Whether first database use applies pending schema migrations (default {@code true}). With
   * {@code false}, it only validates recorded migration versions and checksums, without DDL.
   * Missing or mismatched history fails with a {@link BikeschedException}. This is not a check for
   * physical schema drift. {@link Bikesched#migrate()} still migrates explicitly.
   *
   * @param migrateSchema whether to migrate automatically
   * @return this builder
   */
  public BikeschedBuilder migrateSchema(boolean migrateSchema) {
    this.migrateSchema = migrateSchema;
    return this;
  }

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
   * @param nodeName the node name, not blank, at most 200 characters
   * @return this builder
   */
  public BikeschedBuilder nodeName(String nodeName) {
    this.nodeName = ValidationUtil.requireName(nodeName, "node name");
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
   * registered, executes no jobs and sends no heartbeats until {@link Bikesched#start()} is called,
   * e.g. once all job handlers are registered, or never for an instance that only schedules jobs.
   * Until then its {@link Node#id() id} is empty.
   *
   * <p>With {@code false}, {@link #build()} also does not access the database: the schema is
   * migrated (or validated with {@link #migrateSchema(boolean)} off) on first use, i.e. when
   * polling starts or a job is scheduled or an event published, so the instance can be built while
   * the database is not available yet.
   *
   * @param autoStartPolling whether to start polling on {@link #build()}
   * @return this builder
   */
  public BikeschedBuilder autoStartPolling(boolean autoStartPolling) {
    this.autoStartPolling = autoStartPolling;
    return this;
  }

  /**
   * Sets the mapper converting job payloads, step results and event payloads to and from JSON
   * (optional). Without one, bikesched only accepts strings that already contain JSON text and
   * passes them through unchanged; for Jackson, see the {@code bikesched-jackson} module.
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
    this.defaultJobTimeout = ValidationUtil.requirePositive(timeout, "defaultJobTimeout");
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
   * Sets how often this node runs a failure handler before giving up on it (optional, default 3):
   * the {@link org.bikesched.job.JobFailureHandler failure handler of a job name} and the {@link
   * org.bikesched.job.StepBuilder#onFailure(org.bikesched.job.StepFailureHandler) failure handler
   * of a step}. A run counts once it started, so a handler that crashes the node counts too. Once
   * given up, the job ends ({@code FAILED} or {@code COMPENSATION_FAILED}), or the step is {@code
   * FAILED} and the job fails.
   *
   * @param failureHandlerAttempts the number of runs, at least 1
   * @return this builder
   */
  public BikeschedBuilder failureHandlerAttempts(int failureHandlerAttempts) {
    if (failureHandlerAttempts < 1) {
      throw new IllegalArgumentException(
          "failureHandlerAttempts must be >= 1: " + failureHandlerAttempts);
    }
    this.failureHandlerAttempts = failureHandlerAttempts;
    return this;
  }

  /**
   * Sets the default timeout of waits for events (optional, default none: jobs wait until the event
   * is published). Applies to {@link Job#waitForEvent(String, String, String, Class) waits} without
   * a {@link EventWait#timeout(Duration) timeout} of their own, counted from when the step first
   * started waiting. Once it passed, the wait throws an {@link EventTimeoutException} and the job
   * fails without further attempts, unless the wait's {@link
   * EventWait#onFailure(org.bikesched.job.StepFailureHandler) failure handler} resolves it.
   *
   * @param timeout the default timeout, positive
   * @return this builder
   */
  public BikeschedBuilder defaultEventTimeout(Duration timeout) {
    this.defaultEventTimeout = ValidationUtil.requirePositive(timeout, "defaultEventTimeout");
    return this;
  }

  /**
   * Sets how long events published through this node are kept (optional, default 7 days), unless an
   * event sets its own {@link org.bikesched.event.EventBuilder#retention(Duration) retention}. A
   * step that starts waiting for an event receives the latest one whose retention has not passed,
   * so events published before a job waits for them are not lost. Expired events are deleted by the
   * nodes; steps that already received an event keep its payload.
   *
   * @param retention how long events are kept, positive
   * @return this builder
   * @see Job#waitForEvent(String, String, String, Class)
   */
  public BikeschedBuilder eventRetention(Duration retention) {
    this.eventRetention = ValidationUtil.requirePositive(retention, "eventRetention");
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
    this.nodeCleanUpInterval = ValidationUtil.requirePositive(interval, "nodeCleanUpInterval");
    return this;
  }

  /**
   * Sets how often this node deletes expired events, see {@link #eventRetention(Duration)}
   * (optional, default 1 hour). Every polling node does this.
   *
   * @param interval the time between clean-ups, positive
   * @return this builder
   */
  public BikeschedBuilder eventCleanUpInterval(Duration interval) {
    this.eventCleanUpInterval = ValidationUtil.requirePositive(interval, "eventCleanUpInterval");
    return this;
  }

  /**
   * Enables the job history on this node (optional, default off): every change of a job or of one
   * of its steps made through this node is recorded in {@code bikesched_job_history}, in the same
   * transaction as the change, and kept for the given period. Read it with {@link
   * Bikesched#findJobHistory(java.util.UUID)}. Enable it on all nodes: a node without it records
   * nothing, so the history of jobs it touches has gaps.
   *
   * @param retention how long history entries are kept, positive
   * @return this builder
   */
  public BikeschedBuilder jobHistory(Duration retention) {
    this.jobHistoryRetention = ValidationUtil.requirePositive(retention, "jobHistory retention");
    return this;
  }

  /**
   * Sets how often this node deletes job history entries older than the {@linkplain
   * #jobHistory(Duration) retention} (optional, default 1 hour). Only nodes with the job history
   * enabled do this.
   *
   * @param interval the time between clean-ups, positive
   * @return this builder
   */
  public BikeschedBuilder jobHistoryCleanUpInterval(Duration interval) {
    this.jobHistoryCleanUpInterval =
        ValidationUtil.requirePositive(interval, "jobHistoryCleanUpInterval");
    return this;
  }

  /**
   * Deletes ended jobs ({@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED}) with their steps once
   * they finished longer than the given period ago (optional, default 7 days). Every polling node
   * does this every {@link #jobCleanUpInterval(Duration)}; several do not get in each other's way,
   * so give all nodes the same retention. Pending and running jobs, including the pending
   * occurrences of recurring jobs, are never deleted; the job history has its own {@link
   * #jobHistory(Duration) retention}.
   *
   * @param retention how long ended jobs are kept, positive
   * @return this builder
   */
  public BikeschedBuilder jobRetention(Duration retention) {
    this.jobRetention = ValidationUtil.requirePositive(retention, "jobRetention");
    return this;
  }

  /**
   * Sets how often this node deletes ended jobs older than the {@linkplain #jobRetention(Duration)
   * job retention} (optional, default 1 hour). Every polling node does this.
   *
   * @param interval the time between clean-ups, positive
   * @return this builder
   */
  public BikeschedBuilder jobCleanUpInterval(Duration interval) {
    this.jobCleanUpInterval = ValidationUtil.requirePositive(interval, "jobCleanUpInterval");
    return this;
  }

  /**
   * Limits how long a single database statement of this node may run (optional, default: whatever
   * the data source does). A statement still running then is cancelled and fails like any other
   * database error, so a hanging statement cannot block polling, heartbeats or job execution
   * indefinitely. Applied as JDBC query timeout, in whole seconds (rounded up); statements of
   * {@linkplain JobBuilder#connection(java.sql.Connection) transactional scheduling} on a caller's
   * connection get it too.
   *
   * @param timeout the maximum duration of a statement, positive
   * @return this builder
   */
  public BikeschedBuilder statementTimeout(Duration timeout) {
    this.statementTimeout = ValidationUtil.requirePositive(timeout, "statementTimeout");
    return this;
  }

  /**
   * Abandons attempts whose handler ignores the interrupt of its timeout (optional, default off):
   * if the handler has not returned this long after the attempt's timeout (its own or {@link
   * #defaultJobTimeout(Duration)}) interrupted it, the attempt is recorded as failed like a timed
   * out one (retries and compensation apply as usual), its concurrency slot is freed and the stack
   * trace of its thread is logged. The thread itself cannot be stopped and keeps running; its later
   * step writes are rejected, and its outcome is not recorded once it returns. Only applies to
   * attempts with a timeout.
   *
   * @param grace how long after the timeout to wait for the handler, positive
   * @return this builder
   */
  public BikeschedBuilder hardTimeoutGrace(Duration grace) {
    this.hardTimeoutGrace = ValidationUtil.requirePositive(grace, "hardTimeoutGrace");
    return this;
  }

  /**
   * Observes the jobs this node runs, e.g. {@code MicrometerJobListener} from {@code
   * bikesched-micrometer} for metrics (optional, default none).
   *
   * @param jobListener the listener
   * @return this builder
   */
  public BikeschedBuilder jobListener(JobListener jobListener) {
    this.jobListener = Objects.requireNonNull(jobListener, "jobListener");
    return this;
  }

  /**
   * Observes the events published through this node, e.g. {@code MicrometerEventListener} from
   * {@code bikesched-micrometer} for metrics (optional, default none). Events published in the
   * caller's transaction are not reported.
   *
   * @param eventListener the listener
   * @return this builder
   */
  public BikeschedBuilder eventListener(EventListener eventListener) {
    this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
    return this;
  }

  /**
   * Sets the parsers for recurrence expressions in {@link JobBuilder#schedule(String)} and in
   * {@code @JobHandler(schedule = …)} (optional, default: {@link RecurrenceParser#isoDuration()}
   * only). Replaces the parsers set so far, including the default; add {@code isoDuration()} to
   * keep ISO-8601 durations, e.g. {@code recurrenceParsers(RecurrenceParser.isoDuration(), new
   * CronRecurrenceParser())} with {@code bikesched-cron}. An expression must be supported by
   * exactly one of them.
   *
   * @param recurrenceParsers the parsers
   * @return this builder
   */
  public BikeschedBuilder recurrenceParsers(RecurrenceParser... recurrenceParsers) {
    this.recurrenceParsers = List.of(recurrenceParsers);
    return this;
  }

  /**
   * Interval between the heartbeats this node writes while it polls; at most half the {@link
   * #nodeTimeout(Duration)}. Lower values detect a crashed node sooner at the cost of one write per
   * interval; defaults to 5 seconds.
   *
   * @param heartbeatInterval the interval, positive
   * @return this builder
   */
  public BikeschedBuilder heartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = ValidationUtil.requirePositive(heartbeatInterval, "heartbeatInterval");
    return this;
  }

  /**
   * Time without heartbeat after which a node is considered dead: its running jobs are released.
   * Must be at least twice the {@link #heartbeatInterval(Duration)} (tolerate the interval plus a
   * missed heartbeat, e.g. several intervals for nodes with long GC pauses); defaults to 30
   * seconds.
   *
   * @param nodeTimeout the timeout, positive and at least twice the heartbeat interval
   * @return this builder
   */
  public BikeschedBuilder nodeTimeout(Duration nodeTimeout) {
    this.nodeTimeout = ValidationUtil.requirePositive(nodeTimeout, "nodeTimeout");
    return this;
  }

  /**
   * Interval between two polls for due jobs; defaults to 10 seconds. While nodes {@linkplain
   * #listenForDueJobs(boolean) listen for due jobs}, it is the safety net for missed notifications
   * and bounds how late a delayed job scheduled by another node (or on a caller's connection)
   * starts. Nodes that don't listen start jobs made due by other nodes up to one interval late, so
   * they should poll more often (e.g. every 5 seconds or less). Every poll is a query on the job
   * table, so the load scales with the number of nodes.
   *
   * @param pollInterval the interval, positive
   * @return this builder
   */
  public BikeschedBuilder pollInterval(Duration pollInterval) {
    this.pollInterval = ValidationUtil.requirePositive(pollInterval, "pollInterval");
    return this;
  }

  /**
   * Whether the node listens for due jobs and notifies the other nodes about the jobs it makes due
   * now (PostgreSQL {@code LISTEN/NOTIFY}); defaults to {@code true}. Then a job starts as soon as
   * it is due instead of on the next poll: notifications reach the other nodes within the {@link
   * #notifyInterval(Duration) notify interval}, and every node wakes itself up for the delayed jobs
   * it writes. A listening node holds one connection of the data source for its lifetime, so size a
   * pool one connection larger.
   *
   * <p>Turn it off where notifications cannot arrive, e.g. behind PgBouncer in transaction mode (a
   * listening node warns at startup if its own notification does not arrive), and then lower the
   * {@link #pollInterval(Duration) poll interval}. Set it the same on all nodes and clients: the
   * writes of a node that doesn't notify reach the others only on their next poll.
   *
   * @param listenForDueJobs {@code false} to neither listen nor notify
   * @return this builder
   */
  public BikeschedBuilder listenForDueJobs(boolean listenForDueJobs) {
    this.listenForDueJobs = listenForDueJobs;
    return this;
  }

  /**
   * How long a node collects the job names it made due now before it notifies the other nodes in
   * one statement; defaults to 100 milliseconds. It is the extra latency of such jobs on other
   * nodes, and limits the notifying commits of a node to one per interval (they are serialized
   * database-wide). An idle node sends nothing.
   *
   * @param notifyInterval the interval, positive
   * @return this builder
   */
  public BikeschedBuilder notifyInterval(Duration notifyInterval) {
    this.notifyInterval = ValidationUtil.requirePositive(notifyInterval, "notifyInterval");
    return this;
  }

  /**
   * Creates the {@link Bikesched} instance: migrates or validates the schema and, unless disabled
   * via {@link #autoStartPolling(boolean)}, {@linkplain Bikesched#start() starts polling}, which
   * registers the node and starts its heartbeat. With polling disabled, this method does not access
   * the database: migration or validation runs on first use instead.
   *
   * @return the new instance
   * @throws IllegalStateException if no data source was set
   * @throws BikeschedException if polling is enabled and migration, validation or node registration
   *     fails, e.g. because the database is not available
   */
  public Bikesched build() {
    if (dataSource == null) {
      throw new IllegalStateException("dataSource is required");
    }
    if (nodeTimeout.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
      throw new IllegalArgumentException(
          "nodeTimeout ("
              + nodeTimeout
              + ") must be at least twice the heartbeatInterval ("
              + heartbeatInterval
              + "), otherwise a healthy node's jobs are released");
    }
    String name = nodeName != null ? nodeName : localHostName();
    var settings =
        new Settings(
            name,
            heartbeatInterval,
            nodeTimeout,
            pollInterval,
            listenForDueJobs,
            notifyInterval,
            maxConcurrentJobs,
            autoStartPolling,
            migrateSchema,
            defaultJobTimeout,
            retryBackoff,
            failureHandlerAttempts,
            eventRetention,
            defaultEventTimeout,
            nodeCleanUpInterval,
            eventCleanUpInterval,
            jobHistoryRetention,
            jobHistoryCleanUpInterval,
            jobRetention,
            jobCleanUpInterval,
            statementTimeout,
            hardTimeoutGrace,
            jobListener,
            eventListener,
            recurrenceParsers);
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
