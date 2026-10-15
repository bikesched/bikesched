// SPDX-License-Identifier: Apache-2.0
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
import org.bikesched.Bikesched;
import org.bikesched.BikeschedException;
import org.bikesched.BikeschedLogContext;
import org.bikesched.Node;
import org.bikesched.admin.Admin;
import org.bikesched.event.EventBuilder;
import org.bikesched.event.EventType;
import org.bikesched.event.NodeEventBuilder;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.DatabaseInitializer;
import org.bikesched.internal.database.EventStore;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.event.DefaultEventBuilder;
import org.bikesched.internal.job.DefaultJobBuilder;
import org.bikesched.internal.job.DueJobsNotifier;
import org.bikesched.internal.job.JobRegistry;
import org.bikesched.internal.job.JobScheduler;
import org.bikesched.internal.job.RecurrenceParsers;
import org.bikesched.internal.job.RecurringJob;
import org.bikesched.internal.job.claim.JobPoller;
import org.bikesched.internal.job.claim.JobPollerSettings;
import org.bikesched.internal.node.DefaultNode;
import org.bikesched.internal.node.NodeCleanUp;
import org.bikesched.internal.util.ExecutorUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.internal.util.Version;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobType;
import org.bikesched.job.TypedJobFailureHandler;
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
 *   <li>{@code bikesched-heartbeat}: single scheduled thread updating this node's heartbeat while
 *       the node polls. A node that does not poll executes no jobs and sends no heartbeats
 *   <li>{@code bikesched-cleanup}: single scheduled thread running the housekeeping while the node
 *       polls, each on its own interval: the {@link NodeCleanUp} (releases the running jobs of dead
 *       nodes, removes dead nodes), deleting expired events, deleting expired history entries (with
 *       the job history enabled) and deleting ended jobs after the job retention; also retries of
 *       the recurring job repair (recreates missing occurrences of the node's recurring jobs),
 *       which runs once when polling starts
 *   <li>the threads of the {@link JobPoller}, which claims and runs jobs while the node polls
 *   <li>{@code bikesched-notifier}: the {@link DueJobsNotifier}, which tells the nodes about the
 *       jobs this node's writes made due
 * </ul>
 *
 * <p>The job types the node knows (handlers, recurring jobs, payload classes) are in its {@link
 * JobRegistry}.
 */
public final class DefaultBikesched implements Bikesched {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

  /** Ended jobs deleted per statement by the job clean-up. */
  private static final int JOB_CLEAN_UP_BATCH_SIZE = 1000;

  private final Database database;
  private final Settings settings;
  private final String nodeName;
  private final JobRegistry registry = new JobRegistry();
  private final JobPoller jobPoller;
  private final JobScheduler jobScheduler;
  private final DueJobsNotifier notifier;
  private final ScheduledExecutorService heartbeatExecutor;
  private final ScheduledThreadPoolExecutor cleanUpExecutor;
  private final DefaultNode node;
  private final DefaultAdmin admin;

  /** Guards starting to poll against closing, so a node never registers after it was closed. */
  private final Object lifecycle = new Object();

  /** Called before every database access of the API (polling, scheduling, publishing events). */
  private final DatabaseInitializer initializer;

  private DefaultBikesched(Database database, Settings settings) {
    this.database = database;
    this.initializer = new DatabaseInitializer(database, settings.migrateSchema());
    this.settings = settings;
    this.nodeName = settings.nodeName();
    this.node = new DefaultNode(nodeName);
    this.admin =
        new DefaultAdmin(
            database.admin(), settings.nodeTimeout(), this::ensureReady, registry.types());
    this.jobScheduler =
        new JobScheduler(
            database,
            registry,
            nodeName,
            new RecurrenceParsers(settings.recurrenceParsers()),
            this::ensureReady);
    this.jobPoller =
        new JobPoller(
            database,
            new JobPollerSettings(
                settings.nodeName(),
                settings.maxConcurrentJobs(),
                settings.pollInterval(),
                settings.listenForDueJobs(),
                settings.defaultJobTimeout(),
                settings.defaultEventTimeout(),
                settings.eventRetention(),
                settings.retryBackoff(),
                settings.failureHandlerAttempts(),
                settings.hardTimeoutGrace(),
                settings.jobListener(),
                settings.eventListener()),
            registry,
            jobScheduler);
    this.notifier =
        new DueJobsNotifier(
            database.jobs(),
            settings.notifyInterval(),
            settings.listenForDueJobs(),
            jobPoller,
            nodeName);
    database.reportDueJobs(notifier);
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
        bikesched.start();
      } catch (RuntimeException e) {
        bikesched.close(); // its executors
        throw e;
      }
    } else {
      LOG.info(
          "bikesched node {} created; it registers, sends heartbeats and polls for jobs once"
              + " start() is called",
          bikesched.nodeName);
    }
    return bikesched;
  }

  @Override
  public Node start() {
    ensureReady(); // not under the lifecycle lock: close() must not wait for it
    synchronized (lifecycle) {
      node.requireOpen();
      if (node.id().isPresent()) {
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
      var nodeCleanUp =
          new NodeCleanUp(
              database,
              id,
              nodeName,
              settings.nodeTimeout(),
              settings.nodeCleanUpInterval(),
              jobPoller::withActiveJobIds);
      node.registered(id);
      // Once per node lifetime, before anything is scheduled; retried until it succeeds.
      repairRecurringJobs();
      // A polling node claims jobs, so it must heartbeat: otherwise other nodes would consider it
      // dead and release its running jobs.
      every(heartbeatExecutor, settings.heartbeatInterval(), "Heartbeat", () -> heartbeat(id));
      every(cleanUpExecutor, settings.nodeCleanUpInterval(), "Node clean-up", nodeCleanUp::run);
      every(
          cleanUpExecutor,
          settings.eventCleanUpInterval(),
          "Deleting expired events",
          () -> {
            int deleted = database.events().deleteExpiredEvents();
            if (deleted > 0) {
              LOG.debug("Deleted {} expired event(s)", deleted);
            }
          });
      Duration historyRetention = settings.jobHistoryRetention();
      if (historyRetention != null) {
        every(
            cleanUpExecutor,
            settings.jobHistoryCleanUpInterval(),
            "Deleting old job history",
            () -> {
              int deleted = database.history().deleteHistory(historyRetention);
              if (deleted > 0) {
                LOG.debug(
                    "Deleted {} job history entries older than {}", deleted, historyRetention);
              }
            });
        LOG.info(
            "bikesched node {} records the job history, keeping entries for {}",
            nodeName,
            historyRetention);
      }
      every(
          cleanUpExecutor,
          settings.jobCleanUpInterval(),
          "Deleting finished jobs",
          this::deleteFinishedJobs);
      jobPoller.start(id);
      LOG.info(
          "bikesched node {} ({}) registered, {}polling for jobs every {} (running up to {}"
              + " concurrently) and heartbeating every {} (bikesched {})",
          nodeName,
          id,
          settings.listenForDueJobs() ? "listening for due jobs, " : "",
          settings.pollInterval(),
          settings.maxConcurrentJobs(),
          settings.heartbeatInterval(),
          Version.get());
      return node;
    }
  }

  @Override
  public Node node() {
    return node;
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
  public Bikesched registerJobFailureHandler(String type, JobFailureHandler handler) {
    requireValidType(type);
    Objects.requireNonNull(handler, "handler");
    if (!registry.registerFailureHandler(type, handler)) {
      throw new IllegalArgumentException(
          "A failure handler for job type '" + type + "' is already registered");
    }
    LOG.debug("bikesched node {} registered failure handler for job type {}", nodeName, type);
    return this;
  }

  @Override
  public <T> Bikesched registerJobFailureHandler(
      JobType<T> type, TypedJobFailureHandler<? super T> handler) {
    Objects.requireNonNull(handler, "handler");
    registry.requirePayloadType(type);
    return registerJobFailureHandler(type.name(), JobRegistry.typed(handler, type.payloadType()));
  }

  @Override
  public JobBuilder<Object> newJob(String type) {
    requireValidType(type);
    return new DefaultJobBuilder<>(type, Object.class, database.jsonMapper(), jobScheduler);
  }

  @Override
  public <T> JobBuilder<T> newJob(JobType<T> type) {
    registry.requirePayloadType(type);
    return new DefaultJobBuilder<>(
        type.name(), type.payloadType(), database.jsonMapper(), jobScheduler);
  }

  @Override
  public void migrate() {
    node.requireOpen();
    initializer.migrate();
  }

  /**
   * Prepares an access to the database: checks that this node is open and migrates or validates the
   * schema on first use.
   *
   * @throws IllegalStateException if this node is closed
   */
  private void ensureReady() {
    node.requireOpen();
    initializer.ensureInitialized();
  }

  @Override
  public boolean removeRecurringJob(String type) {
    requireValidType(type);
    return jobScheduler.removeRecurringJob(type);
  }

  @Override
  public NodeEventBuilder<Object> newEvent(String eventName, String eventKey) {
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultEventBuilder<>(eventName, eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public <T> NodeEventBuilder<T> newEvent(EventType<T> eventType, String eventKey) {
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
  public Admin admin() {
    return admin;
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
  private UUID publish(
      String eventName,
      String eventKey,
      String payload,
      Duration eventRetention,
      String idempotencyKey,
      Connection connection) {
    ensureReady();
    Duration retention = eventRetention != null ? eventRetention : settings.eventRetention();
    EventStore.Publication publication =
        connection == null
            ? database
                .events()
                .publishEvent(eventName, eventKey, payload, retention, idempotencyKey)
            : database
                .events()
                .publishEventInTransaction(
                    connection, eventName, eventKey, payload, retention, idempotencyKey);
    if (publication.duplicate()) {
      LOG.debug(
          "Event {}/{} with idempotency key {} already published as {}, not publishing it again",
          eventName,
          eventKey,
          idempotencyKey,
          publication.eventId());
      return publication.eventId();
    }
    LOG.debug(
        "Published event {} {}/{}{}, received by {} waiting step(s)",
        publication.eventId(),
        eventName,
        eventKey,
        connection == null ? "" : " in the caller's transaction",
        publication.received());
    if (connection == null) {
      // Events in the caller's transaction are not reported: it may still roll back.
      notifyPublished(eventName, publication);
    }
    return publication.eventId();
  }

  /** Reports a committed publication; the listener's exceptions must not affect publishing. */
  private void notifyPublished(String eventName, EventStore.Publication publication) {
    try {
      settings
          .eventListener()
          .onEventPublished(
              new EventPublication(
                  publication.eventId(), eventName, EventSource.NODE, publication.received()));
    } catch (RuntimeException e) {
      LOG.warn("Event listener failed for event {}, ignoring it", publication.eventId(), e);
    }
  }

  /**
   * Stops polling, lets in-flight jobs finish (up to a timeout), releases jobs that are still
   * running back to {@code SCHEDULED}, stops the heartbeat and deregisters the node.
   */
  @Override
  public void close() {
    UUID id;
    synchronized (lifecycle) {
      if (!node.close()) {
        return;
      }
      id = node.id().orElse(null);
    }
    jobPoller.close();
    ExecutorUtil.shutdownAndAwait(cleanUpExecutor, "clean-up");
    ExecutorUtil.shutdownAndAwait(heartbeatExecutor, "heartbeat");
    if (id == null) {
      notifier.close(); // what this client made due
      LOG.info("bikesched node {} closed (it never polled, so it was not registered)", nodeName);
      return;
    }
    deregister(id);
    notifier.close(); // after releasing the node's jobs, so that other nodes take them over now
  }

  private void deregister(UUID id) {
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

  /**
   * Runs the task every interval with this node's id in the MDC. A failing run is logged, the next
   * one runs as usual.
   *
   * @param what what the task does, for the warning
   */
  private void every(
      ScheduledExecutorService executor, Duration interval, String what, Runnable task) {
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(
        () -> withNodeId(what, task), millis, millis, TimeUnit.MILLISECONDS);
  }

  private void withNodeId(String what, Runnable task) {
    UUID id = node.id().orElse(null);
    try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(id))) {
      task.run();
    } catch (RuntimeException e) {
      LOG.warn("{} failed on bikesched node {} ({})", what, nodeName, id, e);
    }
  }

  /** Updates this node's heartbeat, or registers the node again if other nodes removed it. */
  private void heartbeat(UUID id) {
    if (database.nodes().heartbeat(id)) {
      LOG.trace("Heartbeat of bikesched node {} ({})", nodeName, id);
    } else if (database.nodes().reregisterNode(id, nodeName)) {
      // Other nodes removed it after missing its heartbeats (e.g. a long pause) and released its
      // jobs; without registering again, every claim would fail on the missing node.
      LOG.warn(
          "bikesched node {} ({}) was removed after missing heartbeats, registered it again",
          nodeName,
          id);
    } else {
      LOG.warn(
          "bikesched node {} ({}) is no longer registered and its name is held by another node",
          nodeName,
          id);
    }
  }

  /** Deletes ended jobs older than the job retention, in batches to keep each statement short. */
  private void deleteFinishedJobs() {
    Duration retention = settings.jobRetention();
    int deleted = 0;
    int batch;
    do {
      batch = database.jobs().deleteFinishedJobs(retention, JOB_CLEAN_UP_BATCH_SIZE);
      deleted += batch;
    } while (batch == JOB_CLEAN_UP_BATCH_SIZE && !Thread.currentThread().isInterrupted());
    if (deleted > 0) {
      LOG.debug("Deleted {} job(s) finished more than {} ago", deleted, retention);
    }
  }

  /**
   * Schedules a pending occurrence for every recurring job of this node that has none, e.g. after
   * its occurrence was deleted by hand. If that fails, it runs again on the clean-up thread after
   * the node clean-up interval, until it succeeds or the node is closed.
   */
  private void repairRecurringJobs() {
    boolean succeeded = true;
    for (RecurringJob recurring : registry.recurringJobs()) {
      try {
        Instant now = Instant.now();
        NewJob occurrence = recurring.occurrence(recurring.nextRun(now, now));
        if (database.jobs().insertOccurrence(occurrence)) {
          LOG.info(
              "Recurring job {} had no pending occurrence, scheduled one at {}",
              recurring.type(),
              occurrence.scheduledAt());
        }
      } catch (RuntimeException e) {
        LOG.warn("Checking recurring job {} failed", recurring.type(), e);
        succeeded = false;
      }
    }
    if (succeeded) {
      return;
    }
    Duration retryIn = settings.nodeCleanUpInterval();
    LOG.warn("Repairing recurring jobs failed, retrying in {}", retryIn);
    try {
      cleanUpExecutor.schedule(
          () -> withNodeId("Repairing recurring jobs", this::repairRecurringJobs),
          retryIn.toMillis(),
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      LOG.debug("Not retrying the recurring job repair: bikesched node {} is closing", nodeName);
    }
  }

  private static void requireValidType(String type) {
    ValidationUtil.requireName(type, "job type");
  }
}
