package org.bikesched.internal;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedException;
import org.bikesched.BikeschedLogContext;
import org.bikesched.Node;
import org.bikesched.event.EventBuilder;
import org.bikesched.event.EventType;
import org.bikesched.event.ReceivedEvent;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.DatabaseInitializer;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.event.DefaultEventBuilder;
import org.bikesched.internal.event.EventCleanUp;
import org.bikesched.internal.job.BaseJobBuilder;
import org.bikesched.internal.job.DefaultJobBuilder;
import org.bikesched.internal.job.HistoryCleanUp;
import org.bikesched.internal.job.JobCleanUp;
import org.bikesched.internal.job.JobPoller;
import org.bikesched.internal.job.JobPollerSettings;
import org.bikesched.internal.job.JobRegistry;
import org.bikesched.internal.job.RecurringJob;
import org.bikesched.internal.job.RecurringJobRepair;
import org.bikesched.internal.node.NodeCleanUp;
import org.bikesched.internal.node.NodeHeartbeat;
import org.bikesched.internal.util.ExecutorUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobType;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Default {@link Bikesched} implementation. All database access goes through {@link Database}.
 *
 * <p>Threads:
 *
 * <ul>
 *   <li>{@code bikesched-heartbeat}: single scheduled thread running the {@link NodeHeartbeat}
 *       while the node polls, updating this node's heartbeat. A node that does not poll executes no
 *       jobs and sends no heartbeats
 *   <li>{@code bikesched-cleanup}: single scheduled thread running the housekeeping while the node
 *       polls, each on its own interval: the {@link NodeCleanUp} (releases the running jobs of dead
 *       nodes, removes dead nodes), the {@link EventCleanUp} (deletes expired events) and, with the
 *       job history enabled, the {@link HistoryCleanUp} (deletes expired history entries) and the
 *       {@link JobCleanUp} (deletes ended jobs after the job retention); also retries of the {@link
 *       RecurringJobRepair} (recreates missing occurrences of the node's recurring jobs), which
 *       runs once when polling starts
 *   <li>the threads of the {@link JobPoller}, which claims and runs jobs while the node polls
 * </ul>
 *
 * <p>The job types the node knows (handlers, recurring jobs, payload classes) are in its {@link
 * JobRegistry}.
 */
public final class DefaultBikesched implements Bikesched {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

  private final Database database;
  private final Settings settings;
  private final String nodeName;
  private final JobRegistry registry = new JobRegistry();
  private final JobPoller jobPoller;
  private final ScheduledExecutorService heartbeatExecutor;
  private final ScheduledThreadPoolExecutor cleanUpExecutor;
  private final Node node = new DefaultNode();

  /** Guards starting to poll against closing, so a node never registers after it was closed. */
  private final Object lifecycle = new Object();

  private final AtomicBoolean closed = new AtomicBoolean();

  /** Set (once) when polling starts; {@code null} until then. */
  private volatile UUID nodeId;

  /** Called before every database access of the API (polling, scheduling, publishing events). */
  private final DatabaseInitializer initializer;

  private DefaultBikesched(Database database, Settings settings) {
    this.database = database;
    this.initializer = new DatabaseInitializer(database);
    this.settings = settings;
    this.nodeName = settings.nodeName();
    this.jobPoller =
        new JobPoller(
            database,
            new JobPollerSettings(
                settings.nodeName(),
                settings.maxConcurrentJobs(),
                settings.pollInterval(),
                settings.defaultJobTimeout(),
                settings.defaultEventTimeout(),
                settings.retryBackoff(),
                settings.hardTimeoutGrace()),
            registry);
    this.heartbeatExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("bikesched-heartbeat").daemon().factory());
    this.cleanUpExecutor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("bikesched-cleanup").daemon().factory());
    // close() must not wait for the one-time recurring job repair if it has not started yet.
    this.cleanUpExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /**
   * Creates the instance. The database is not accessed until it is needed (see {@link
   * DatabaseInitializer}): with polling enabled, that is right away, since polling starts (which
   * registers the node and starts its heartbeat) before this method returns.
   *
   * @param database the database
   * @param settings the node's settings
   * @return the instance
   */
  public static DefaultBikesched create(Database database, Settings settings) {
    var bikesched = new DefaultBikesched(database, settings);
    if (settings.autoStartPolling()) {
      try {
        bikesched.startPolling();
      } catch (RuntimeException e) {
        bikesched.close(); // its executors
        throw e;
      }
    } else {
      LOG.info(
          "bikesched node {} created; it registers, sends heartbeats and polls for jobs once"
              + " startPolling() is called",
          bikesched.nodeName);
    }
    return bikesched;
  }

  @Override
  public Node startPolling() {
    ensureReady(); // not under the lifecycle lock: close() must not wait for it
    synchronized (lifecycle) {
      requireOpen();
      if (nodeId != null) {
        return node;
      }
      UUID id =
          database
              .nodes()
              .registerNode(UUID.randomUUID(), nodeName, settings.nodeTimeout())
              .orElseThrow(
                  () ->
                      new BikeschedException(
                          "A live bikesched node named '"
                              + nodeName
                              + "' is already registered; node names must be unique"));
      // This node runs nothing yet: jobs still RUNNING under this id belong to a previous
      // (crashed) instance with the same name whose row was taken over.
      int orphaned = database.jobs().releaseJobs(id);
      if (orphaned > 0) {
        LOG.warn(
            "Released {} job(s) left running by a previous instance of bikesched node {} ({})",
            orphaned,
            nodeName,
            id);
      }
      var heartbeat = new NodeHeartbeat(database, id, nodeName);
      var nodeCleanUp = new NodeCleanUp(database, id, nodeName, settings.nodeTimeout());
      var recurringJobRepair = new RecurringJobRepair(database, registry::recurringJobs);
      var eventCleanUp = new EventCleanUp(database, id, nodeName);
      nodeId = id;
      // Once per node lifetime, before anything is scheduled; retried until it succeeds.
      repairRecurringJobs(recurringJobRepair);
      // A polling node claims jobs, so it must heartbeat: otherwise other nodes would consider it
      // dead and release its running jobs.
      scheduleWithFixedDelay(heartbeatExecutor, heartbeat::run, settings.heartbeatInterval());
      scheduleWithFixedDelay(cleanUpExecutor, nodeCleanUp::run, settings.nodeCleanUpInterval());
      scheduleWithFixedDelay(cleanUpExecutor, eventCleanUp::run, settings.eventCleanUpInterval());
      Duration historyRetention = settings.jobHistoryRetention();
      if (historyRetention != null) {
        var historyCleanUp = new HistoryCleanUp(database, id, nodeName, historyRetention);
        scheduleWithFixedDelay(
            cleanUpExecutor, historyCleanUp::run, settings.jobHistoryCleanUpInterval());
        LOG.info(
            "bikesched node {} records the job history, keeping entries for {}",
            nodeName,
            historyRetention);
      }
      var jobCleanUp = new JobCleanUp(database, id, nodeName, settings.jobRetention());
      scheduleWithFixedDelay(cleanUpExecutor, jobCleanUp::run, settings.jobCleanUpInterval());
      jobPoller.start(id);
      LOG.info(
          "bikesched node {} ({}) registered, polling for jobs every {} (running up to {}"
              + " concurrently) and heartbeating every {}",
          nodeName,
          id,
          settings.pollInterval(),
          settings.maxConcurrentJobs(),
          settings.heartbeatInterval());
      return node;
    }
  }

  @Override
  public Node node() {
    return node;
  }

  /** The {@link Node} view of this instance. */
  private final class DefaultNode implements Node {

    @Override
    public String name() {
      return nodeName;
    }

    @Override
    public Optional<UUID> id() {
      return Optional.ofNullable(nodeId);
    }

    @Override
    public boolean isPolling() {
      return nodeId != null && !closed.get();
    }

    @Override
    public String toString() {
      return "Node[name=" + nodeName + ", id=" + nodeId + "]";
    }
  }

  @Override
  public Bikesched registerJobHandler(String type, JobHandler handler) {
    requireValidType(type);
    Objects.requireNonNull(handler, "handler");
    if (!registry.register(type, handler)) {
      throw new IllegalArgumentException(
          "A handler for job type '" + type + "' is already registered");
    }
    LOG.debug("bikesched node {} registered handler for job type {}", nodeName, type);
    return this;
  }

  @Override
  public <T> Bikesched registerJobHandler(JobType<T> type, TypedJobHandler<? super T> handler) {
    Objects.requireNonNull(handler, "handler");
    registry.requirePayloadType(type);
    Class<T> payloadType = type.payloadType();
    return registerJobHandler(type.name(), job -> handler.handle(job, job.payload(payloadType)));
  }

  @Override
  public JobBuilder<Object> newJob(String type) {
    requireValidType(type);
    return new DefaultJobBuilder<>(type, Object.class, database.jsonMapper(), scheduler);
  }

  @Override
  public <T> JobBuilder<T> newJob(JobType<T> type) {
    registry.requirePayloadType(type);
    return new DefaultJobBuilder<>(
        type.name(), type.payloadType(), database.jsonMapper(), scheduler);
  }

  /** Stores the jobs built by the job builders. */
  private final BaseJobBuilder.Scheduler scheduler =
      new BaseJobBuilder.Scheduler() {
        @Override
        public UUID schedule(NewJob job, JobHandler handler, Connection connection) {
          return scheduleJob(job, handler, connection);
        }

        @Override
        public UUID scheduleRecurring(RecurringJob job, JobHandler handler, Instant firstRun) {
          return scheduleRecurringJob(job, handler, firstRun);
        }
      };

  /**
   * Checks that this node is open.
   *
   * @throws IllegalStateException if this node is closed
   */
  private void requireOpen() {
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
  }

  /**
   * Prepares an access to the database: checks that this node is open and migrates the schema on
   * first use.
   *
   * @throws IllegalStateException if this node is closed
   */
  private void ensureReady() {
    requireOpen();
    initializer.ensureInitialized();
  }

  /**
   * Checks that this node is open and the job type has a handler, registering the given one first
   * if there is none yet.
   */
  private void requireHandler(String type, JobHandler handler) {
    requireOpen();
    if (handler != null) {
      if (registry.register(type, handler)) {
        LOG.info("bikesched node {} registered handler for job type {}", nodeName, type);
      } else {
        LOG.debug("Handler for job type {} already registered, keeping it", type);
      }
    }
    registry.requireHandler(type);
  }

  /** Registers a recurring job, see {@link JobBuilder#schedule(Recurrence)}. */
  private UUID scheduleRecurringJob(RecurringJob job, JobHandler handler, Instant firstRun) {
    String type = job.type();
    requireHandler(type, handler);
    Instant now = Instant.now();
    Instant first = firstRun != null ? firstRun : job.nextRun(now, now);
    ensureReady();
    var registration = database.jobs().registerRecurringJob(job.occurrence(first));
    registry.addRecurringJob(job);
    String recurrence = job.recurrence().description();
    if (registration.created()) {
      LOG.info(
          "Registered recurring job {} ({}), first occurrence at {}",
          type,
          recurrence,
          registration.scheduledAt());
    } else if (registration.previousRecurrence() != null) {
      LOG.info(
          "Recurrence of recurring job {} changed from '{}' to '{}', next occurrence moved to {}",
          type,
          registration.previousRecurrence(),
          recurrence,
          registration.scheduledAt());
    } else {
      LOG.info(
          "Registered recurring job {} ({}), pending occurrence {} at {}",
          type,
          recurrence,
          registration.state(),
          registration.scheduledAt());
    }
    return registration.jobId();
  }

  @Override
  public boolean removeRecurringJob(String type) {
    requireValidType(type);
    boolean registered = registry.removeRecurringJob(type);
    ensureReady();
    int deleted = database.jobs().deletePendingOccurrences(type);
    LOG.info(
        "Removed recurring job {}{}{}",
        type,
        registered ? " from this node" : "",
        deleted > 0 ? ", deleted its pending occurrence" : ", no pending occurrence to delete");
    return deleted > 0;
  }

  /** Stores a job built by a {@link JobBuilder}; registers its handler first if given and new. */
  private UUID scheduleJob(NewJob job, JobHandler handler, Connection connection) {
    String type = job.type();
    requireHandler(type, handler);
    UUID jobId = UUID.randomUUID();
    ensureReady();
    if (connection == null) {
      database.jobs().insertJob(jobId, job);
    } else {
      database.jobs().insertJob(connection, jobId, job);
    }
    LOG.debug(
        "Scheduled job {} of type {} at {} (priority {}, max {} attempt(s), timeout {}){}",
        jobId,
        type,
        job.scheduledAt(),
        job.priority(),
        job.maxAttempts(),
        job.timeout() == null ? "node default" : job.timeout(),
        connection == null ? "" : " in the caller's transaction");
    return jobId;
  }

  @Override
  public EventBuilder<Object> newEvent(String eventName, String eventKey) {
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultEventBuilder<>(eventName, eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public <T> EventBuilder<T> newEvent(EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultEventBuilder<>(
        eventType.name(), eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public Optional<JobInfo> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    return database.jobs().findJob(jobId).map(JobInfo.class::cast);
  }

  @Override
  public List<JobHistoryEntry> findJobHistory(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    return database.history().findHistory(jobId);
  }

  @Override
  public boolean cancel(UUID jobId, String message) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    boolean requested = database.jobs().requestCancel(jobId, message);
    if (requested) {
      LOG.info("Cancellation of job {} requested{}", jobId, message == null ? "" : ": " + message);
    } else {
      LOG.debug(
          "Job {} cannot be cancelled: it does not exist, has ended or is compensating", jobId);
    }
    return requested;
  }

  @Override
  public boolean retry(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    boolean retried = database.jobs().retryJob(jobId);
    if (retried) {
      LOG.info("Failed job {} scheduled to be retried", jobId);
    } else {
      LOG.debug("Job {} cannot be retried: it does not exist or has not failed", jobId);
    }
    return retried;
  }

  /** Publishes an event built by an {@link EventBuilder}. */
  private List<ReceivedEvent> publish(
      String eventName,
      String eventKey,
      String payload,
      Duration eventRetention,
      Connection connection) {
    ensureReady();
    Duration retention = eventRetention != null ? eventRetention : settings.eventRetention();
    List<ReceivedEvent> received =
        connection == null
            ? database.events().publishEvent(eventName, eventKey, payload, retention)
            : database
                .events()
                .publishEventInTransaction(connection, eventName, eventKey, payload, retention);
    LOG.debug(
        "Published event {}/{}{}, received by {} waiting step(s){}",
        eventName,
        eventKey,
        connection == null ? "" : " in the caller's transaction",
        received.size(),
        received.isEmpty()
            ? ""
            : received.stream()
                .map(r -> r.stepName() + " of job " + r.jobId())
                .collect(java.util.stream.Collectors.joining(", ", ": ", "")));
    return received;
  }

  /**
   * Stops polling, lets in-flight jobs finish (up to a timeout), releases jobs that are still
   * running back to {@code SCHEDULED}, stops the heartbeat and deregisters the node.
   */
  @Override
  public void close() {
    UUID id;
    synchronized (lifecycle) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      id = nodeId;
    }
    jobPoller.close();
    ExecutorUtil.shutdownAndAwait(cleanUpExecutor, "clean-up");
    ExecutorUtil.shutdownAndAwait(heartbeatExecutor, "heartbeat");
    if (id == null) {
      LOG.info("bikesched node {} closed (it never polled, so it was not registered)", nodeName);
      return;
    }
    try {
      int released = database.jobs().releaseJobs(id);
      if (released > 0) {
        LOG.info("Released {} unfinished job(s) of bikesched node {} ({})", released, nodeName, id);
      }
      database.nodes().deregisterNode(id);
      LOG.info("bikesched node {} ({}) deregistered", nodeName, id);
    } catch (BikeschedException e) {
      // The row goes stale and can be taken over by a node with the same name later.
      LOG.warn("Could not deregister bikesched node {} ({})", nodeName, id, e);
    }
  }

  private void scheduleWithFixedDelay(
      ScheduledExecutorService executor, Runnable task, Duration interval) {
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(
        () -> {
          try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(nodeId))) {
            task.run();
          }
        },
        millis,
        millis,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Runs the repair now; if it fails, again on the clean-up thread after the node clean-up
   * interval, until it succeeds or the node is closed.
   */
  private void repairRecurringJobs(RecurringJobRepair repair) {
    if (repair.run()) {
      return;
    }
    Duration retryIn = settings.nodeCleanUpInterval();
    LOG.warn("Repairing recurring jobs failed, retrying in {}", retryIn);
    try {
      scheduleOnce(cleanUpExecutor, () -> repairRecurringJobs(repair), retryIn);
    } catch (RejectedExecutionException e) {
      LOG.debug("Not retrying the recurring job repair: bikesched node {} is closing", nodeName);
    }
  }

  /** Runs the task once after the delay, with this node's id in the MDC. */
  private void scheduleOnce(ScheduledExecutorService executor, Runnable task, Duration delay) {
    executor.schedule(
        () -> {
          try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(nodeId))) {
            task.run();
          }
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private static void requireValidType(String type) {
    ValidationUtil.requireName(type, "job type");
  }
}
