package org.bikesched.internal;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
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
import org.bikesched.internal.job.DefaultJob;
import org.bikesched.internal.job.DefaultJobBuilder;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.internal.job.RecurringJob;
import org.bikesched.internal.job.RecurringJobRepair;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobType;
import org.bikesched.job.RecurringJobBuilder;
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
 *   <li>{@code bikesched-heartbeat}: single scheduled thread running the {@link Heartbeat} while
 *       the node polls, updating this node's heartbeat. A node that does not poll executes no jobs
 *       and sends no heartbeats
 *   <li>{@code bikesched-cleanup}: single scheduled thread running the housekeeping while the node
 *       polls, each on its own interval: the {@link NodeCleanUp} (releases the running jobs of dead
 *       nodes, removes dead nodes) and the {@link EventCleanUp} (deletes expired events); also
 *       retries of the {@link RecurringJobRepair} (recreates missing occurrences of the node's
 *       recurring jobs), which runs once when polling starts
 *   <li>{@code bikesched-poller}: single scheduled thread claiming due jobs (skip locked) and
 *       handing them to the job executor
 *   <li>{@code bikesched-job-N}: one virtual thread per claimed job, processed by the {@link
 *       JobProcessor} (running the {@link JobHandler} registered for its type and recording the
 *       outcome)
 *   <li>{@code bikesched-timeouts}: single scheduled thread interrupting job attempts that exceed
 *       their timeout ({@link JobBuilder#timeout(Duration)}, {@link Settings#defaultJobTimeout()})
 * </ul>
 *
 * <p>Concurrency limit: a semaphore holds one permit per job slot ({@link
 * Settings#maxConcurrentJobs()}). The poller claims at most {@code min(free slots,
 * POLL_BATCH_SIZE)} jobs and skips polling while no slot is free; each job returns its slot when it
 * ends. Jobs are therefore only claimed when they can start immediately and never wait in a local
 * queue while marked {@code RUNNING} in the database.
 */
public final class DefaultBikesched implements Bikesched {

  /** Maximum number of jobs claimed per poll. */
  static final int POLL_BATCH_SIZE = 10;

  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

  private final Database database;
  private final Settings settings;
  private final String nodeName;
  private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();
  private final Map<String, RecurringJob> recurringJobs = new ConcurrentHashMap<>();

  /** Payload classes of the job types used with a {@link JobType} on this node, by name. */
  private final Map<String, Class<?>> payloadTypes = new ConcurrentHashMap<>();

  private final Semaphore jobSlots;
  private final ScheduledExecutorService heartbeatExecutor;
  private final ScheduledThreadPoolExecutor cleanUpExecutor;
  private final ScheduledExecutorService pollerExecutor;
  private final ScheduledThreadPoolExecutor timeoutExecutor;
  private final ExecutorService jobExecutor;
  private final Node node = new DefaultNode();

  /** Guards starting to poll against closing, so a node never registers after it was closed. */
  private final Object lifecycle = new Object();

  private final AtomicBoolean closed = new AtomicBoolean();

  /** Set (once) when polling starts, together with the processor; {@code null} until then. */
  private volatile UUID nodeId;

  private volatile JobProcessor processor;

  /** Called before every database access of the API (polling, scheduling, publishing events). */
  private final DatabaseInitializer initializer;

  private DefaultBikesched(Database database, Settings settings) {
    this.database = database;
    this.initializer = new DatabaseInitializer(database);
    this.settings = settings;
    this.nodeName = settings.nodeName();
    this.jobSlots = new Semaphore(settings.maxConcurrentJobs());
    this.heartbeatExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("bikesched-heartbeat").daemon().factory());
    this.cleanUpExecutor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("bikesched-cleanup").daemon().factory());
    // close() must not wait for the one-time recurring job repair if it has not started yet.
    this.cleanUpExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    this.pollerExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("bikesched-poller").daemon().factory());
    this.timeoutExecutor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("bikesched-timeouts").daemon().factory());
    this.timeoutExecutor.setRemoveOnCancelPolicy(true); // timers of jobs that finished in time
    this.jobExecutor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bikesched-job-", 0).factory());
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
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
    initializer.ensureInitialized(); // not under the lifecycle lock: close() must not wait for it
    synchronized (lifecycle) {
      if (closed.get()) {
        throw new IllegalStateException("bikesched node " + nodeName + " is closed");
      }
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
      processor =
          new JobProcessor(
              database,
              id,
              nodeName,
              handlers::get,
              recurringJobs::get,
              timeoutExecutor,
              settings.defaultJobTimeout(),
              settings.defaultEventTimeout(),
              settings.retryBackoff());
      var heartbeat = new Heartbeat(database, id, nodeName);
      var nodeCleanUp = new NodeCleanUp(database, id, nodeName, settings.nodeTimeout());
      var recurringJobRepair = new RecurringJobRepair(database, recurringJobs::values);
      var eventCleanUp = new EventCleanUp(database, id, nodeName, settings.eventRetention());
      nodeId = id;
      // Once per node lifetime, before anything is scheduled; retried until it succeeds.
      repairRecurringJobs(recurringJobRepair);
      // A polling node claims jobs, so it must heartbeat: otherwise other nodes would consider it
      // dead and release its running jobs.
      scheduleWithFixedDelay(heartbeatExecutor, heartbeat::run, settings.heartbeatInterval());
      scheduleWithFixedDelay(cleanUpExecutor, nodeCleanUp::run, settings.nodeCleanUpInterval());
      scheduleWithFixedDelay(cleanUpExecutor, eventCleanUp::run, settings.eventCleanUpInterval());
      scheduleWithFixedDelay(pollerExecutor, this::poll, settings.pollInterval());
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
    if (handlers.putIfAbsent(type, handler) != null) {
      throw new IllegalArgumentException(
          "A handler for job type '" + type + "' is already registered");
    }
    LOG.debug("bikesched node {} registered handler for job type {}", nodeName, type);
    return this;
  }

  @Override
  public <T> Bikesched registerJobHandler(JobType<T> type, TypedJobHandler<? super T> handler) {
    Objects.requireNonNull(handler, "handler");
    requirePayloadType(type);
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
    requirePayloadType(type);
    return new DefaultJobBuilder<>(
        type.name(), type.payloadType(), database.jsonMapper(), scheduler);
  }

  /**
   * Remembers the payload class of a typed job type; rejects a second payload class for the same
   * name on this node, which would make producers and handlers disagree on the payload.
   */
  private void requirePayloadType(JobType<?> type) {
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
   * Checks that this node is open and the job type has a handler, registering the given one first
   * if there is none yet.
   */
  private void requireHandler(String type, JobHandler handler) {
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
    if (handler != null) {
      if (handlers.putIfAbsent(type, handler) == null) {
        LOG.info("bikesched node {} registered handler for job type {}", nodeName, type);
      } else {
        LOG.debug("Handler for job type {} already registered, keeping it", type);
      }
    }
    if (!handlers.containsKey(type)) {
      throw new IllegalArgumentException(
          "Unknown job type '"
              + type
              + "': no handler registered (known types: "
              + new TreeSet<>(handlers.keySet())
              + ")");
    }
  }

  /** Registers a recurring job built by a {@link RecurringJobBuilder}. */
  private UUID scheduleRecurringJob(RecurringJob job, JobHandler handler, Instant firstRun) {
    String type = job.type();
    requireHandler(type, handler);
    Instant now = Instant.now();
    Instant first = firstRun != null ? firstRun : job.nextRun(now, now);
    initializer.ensureInitialized();
    var registration = database.jobs().registerRecurringJob(job.occurrence(first));
    recurringJobs.put(type, job);
    String schedule = job.schedule().description();
    if (registration.created()) {
      LOG.info(
          "Registered recurring job {} ({}), first occurrence at {}",
          type,
          schedule,
          registration.scheduledAt());
    } else if (registration.previousSchedule() != null) {
      LOG.info(
          "Schedule of recurring job {} changed from '{}' to '{}', next occurrence moved to {}",
          type,
          registration.previousSchedule(),
          schedule,
          registration.scheduledAt());
    } else {
      LOG.info(
          "Registered recurring job {} ({}), pending occurrence {} at {}",
          type,
          schedule,
          registration.state(),
          registration.scheduledAt());
    }
    return registration.jobId();
  }

  @Override
  public boolean removeRecurringJob(String type) {
    requireValidType(type);
    boolean registered = recurringJobs.remove(type) != null;
    initializer.ensureInitialized();
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
    initializer.ensureInitialized();
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
    Objects.requireNonNull(eventName, "eventName");
    if (eventName.isBlank()) {
      throw new IllegalArgumentException("event name must not be blank");
    }
    Objects.requireNonNull(eventKey, "eventKey");
    return new DefaultEventBuilder<>(eventName, eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public <T> EventBuilder<T> newEvent(EventType<T> eventType, String eventKey) {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(eventKey, "eventKey");
    return new DefaultEventBuilder<>(
        eventType.name(), eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public Optional<JobInfo> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
    initializer.ensureInitialized();
    return database.jobs().findJob(jobId).map(JobInfo.class::cast);
  }

  @Override
  public boolean cancel(UUID jobId, String message) {
    Objects.requireNonNull(jobId, "jobId");
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
    initializer.ensureInitialized();
    boolean requested = database.jobs().requestCancel(jobId, message);
    if (requested) {
      LOG.info("Cancellation of job {} requested{}", jobId, message == null ? "" : ": " + message);
    } else {
      LOG.debug(
          "Job {} cannot be cancelled: it does not exist, has ended or is compensating", jobId);
    }
    return requested;
  }

  /** Publishes an event built by an {@link EventBuilder}. */
  private List<ReceivedEvent> publish(
      String eventName, String eventKey, String payload, Connection connection) {
    if (closed.get()) {
      throw new IllegalStateException("bikesched node " + nodeName + " is closed");
    }
    initializer.ensureInitialized();
    List<ReceivedEvent> received =
        connection == null
            ? database.events().publishEvent(eventName, eventKey, payload)
            : database.events().publishEventInTransaction(connection, eventName, eventKey, payload);
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
    shutdownAndAwait(pollerExecutor, "poller");
    shutdownAndAwait(jobExecutor, "job executor");
    timeoutExecutor
        .shutdownNow(); // all jobs ended (or were abandoned): no timeouts left to enforce
    shutdownAndAwait(cleanUpExecutor, "clean-up");
    shutdownAndAwait(heartbeatExecutor, "heartbeat");
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

  private void poll() {
    // Must never throw: an exception would silently cancel all future polls.
    try {
      int free = jobSlots.availablePermits();
      if (free == 0) {
        LOG.debug("bikesched node {} ({}) is at capacity, skipping poll", nodeName, nodeId);
        return;
      }
      if (handlers.isEmpty()) {
        // Avoids claiming (and failing) jobs right after startup, before the application
        // had a chance to register its handlers.
        LOG.debug(
            "bikesched node {} ({}) has no job handlers registered, skipping poll",
            nodeName,
            nodeId);
        return;
      }
      int limit = Math.min(free, POLL_BATCH_SIZE);
      LOG.debug(
          "bikesched node {} ({}) polling for up to {} job(s), {} free slot(s)",
          nodeName,
          nodeId,
          limit,
          free);
      // Never blocks: only this (single) poller thread acquires, job threads only release,
      // so the available permits can only have grown since they were read.
      jobSlots.acquireUninterruptibly(limit);
      List<DefaultJob> jobs = List.of();
      try {
        jobs =
            database.jobs().claimJobs(nodeId, limit).stream()
                .map(claimed -> new DefaultJob(database, claimed))
                .toList();
      } finally {
        jobSlots.release(limit - jobs.size()); // unused slots; all of them if claiming failed
      }
      if (!jobs.isEmpty()) {
        LOG.debug("bikesched node {} ({}) claimed {} job(s)", nodeName, nodeId, jobs.size());
      }
      for (DefaultJob job : jobs) {
        dispatch(job);
      }
    } catch (RuntimeException e) {
      LOG.warn("Polling for jobs failed on bikesched node {} ({})", nodeName, nodeId, e);
    }
  }

  /** Runs a claimed job on its own virtual thread; the job's slot is held until it ends. */
  private void dispatch(DefaultJob job) {
    try {
      jobExecutor.execute(
          () -> {
            try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(nodeId));
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_ID, job.id().toString());
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_TYPE, job.type())) {
              processor.process(job);
            } finally {
              jobSlots.release();
            }
          });
    } catch (RejectedExecutionException e) {
      // Shutting down: the job stays RUNNING in the database and is released in close().
      jobSlots.release();
    }
  }

  private static void shutdownAndAwait(ExecutorService executor, String name) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warn(
            "bikesched {} did not terminate within {}, interrupting it", name, SHUTDOWN_TIMEOUT);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static void requireValidType(String type) {
    Objects.requireNonNull(type, "type");
    if (type.isBlank()) {
      throw new IllegalArgumentException("job type must not be blank");
    }
  }
}
