// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.bikesched.event.NodeEventBuilder;
import org.bikesched.event.TypedEvent;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.bikesched.history.HistoryEntry;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.DatabaseInitializer;
import org.bikesched.internal.database.EventStore;
import org.bikesched.internal.event.DefaultEventBuilder;
import org.bikesched.internal.event.EventCleanUp;
import org.bikesched.internal.job.DefaultJobBuilder;
import org.bikesched.internal.job.DueJobsNotifier;
import org.bikesched.internal.job.HistoryCleanUp;
import org.bikesched.internal.job.JobCleanUp;
import org.bikesched.internal.job.JobRegistry;
import org.bikesched.internal.job.JobScheduler;
import org.bikesched.internal.job.RecurrenceParsers;
import org.bikesched.internal.job.RecurringJobRepair;
import org.bikesched.internal.job.claim.JobPoller;
import org.bikesched.internal.job.claim.JobPollerSettings;
import org.bikesched.internal.node.DefaultNode;
import org.bikesched.internal.node.NodeCleanUp;
import org.bikesched.internal.node.NodeHeartbeat;
import org.bikesched.internal.util.ExecutorUtil;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.internal.util.Version;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobInfo;
import org.bikesched.job.TypedJob;
import org.bikesched.job.TypedJobFailureHandler;
import org.bikesched.job.TypedJobHandler;
import org.jspecify.annotations.Nullable;
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
 *       history enabled, the {@link HistoryCleanUp} (deletes expired history entries) and the
 *       {@link JobCleanUp} (deletes ended jobs after the job retention); also retries of the {@link
 *       RecurringJobRepair} (recreates missing occurrences of the node's recurring jobs), which
 *       runs once when polling starts
 *   <li>the threads of the {@link JobPoller}, which claims and runs jobs while the node polls
 *   <li>{@code bikesched-notifier}: the {@link DueJobsNotifier}, which tells the nodes about the
 *       jobs this node's writes made due
 *   <li>{@code bikesched-shutdown}: with {@link Settings#closeOnShutdown()}, the JVM shutdown hook
 *       closing the node; removed again when the node is closed otherwise
 * </ul>
 *
 * <p>The job names the node knows (handlers, recurring jobs, payload classes) are in its {@link
 * JobRegistry}.
 */
public final class DefaultBikesched implements Bikesched {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultBikesched.class);

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

  /** Released once the first {@link #close()} finished, which later calls wait for. */
  private final CountDownLatch closed = new CountDownLatch(1);

  /** The JVM shutdown hook closing the node; {@code null} without {@code closeOnShutdown}. */
  private final @Nullable Thread shutdownHook;

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
            database.admin(), settings.nodeTimeout(), this::ensureReady, registry.names());
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
    this.shutdownHook =
        settings.closeOnShutdown()
            ? Thread.ofPlatform()
                .name("bikesched-shutdown")
                .unstarted(
                    () -> {
                      LOG.info("JVM shutting down, closing bikesched node {}", nodeName);
                      close();
                    })
            : null;
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
    Thread hook = bikesched.shutdownHook;
    if (hook != null) {
      Runtime.getRuntime().addShutdownHook(hook);
    }
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
      if (node.id() != null) {
        return node;
      }
      UUID id = UuidUtil.timeOrdered();
      database.history().writtenBy(id);
      database.nodes().registerNode(id, nodeName);
      var heartbeat = new NodeHeartbeat(database, id, nodeName);
      var nodeCleanUp =
          new NodeCleanUp(
              database,
              id,
              nodeName,
              settings.nodeTimeout(),
              settings.nodeCleanUpInterval(),
              jobPoller::withActiveJobIds);
      var recurringJobRepair = new RecurringJobRepair(database, registry::recurringJobs);
      var eventCleanUp = new EventCleanUp(database, id, nodeName);
      node.registered(id);
      // Once per node lifetime, before anything is scheduled; retried until it succeeds.
      repairRecurringJobs(recurringJobRepair);
      // A polling node claims jobs, so it must heartbeat: otherwise other nodes would consider it
      // dead and release its running jobs.
      scheduleWithFixedDelay(heartbeatExecutor, heartbeat::run, settings.heartbeatInterval());
      scheduleWithFixedDelay(cleanUpExecutor, nodeCleanUp::run, settings.nodeCleanUpInterval());
      scheduleWithFixedDelay(cleanUpExecutor, eventCleanUp::run, settings.eventCleanUpInterval());
      Duration historyRetention = settings.historyRetention();
      if (historyRetention != null) {
        var historyCleanUp = new HistoryCleanUp(database, id, nodeName, historyRetention);
        scheduleWithFixedDelay(
            cleanUpExecutor, historyCleanUp::run, settings.historyCleanUpInterval());
        LOG.info(
            "bikesched node {} records the history, keeping entries for {}",
            nodeName,
            historyRetention);
      }
      var jobCleanUp = new JobCleanUp(database, id, nodeName, settings.jobRetention());
      scheduleWithFixedDelay(cleanUpExecutor, jobCleanUp::run, settings.jobCleanUpInterval());
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
  public Bikesched registerJobHandler(String name, JobHandler handler) {
    requireValidName(name);
    Objects.requireNonNull(handler, "handler");
    if (!registry.register(name, handler)) {
      throw new IllegalArgumentException(
          "A handler for job name '" + name + "' is already registered");
    }
    LOG.debug("bikesched node {} registered handler for job name {}", nodeName, name);
    return this;
  }

  @Override
  public <T extends @Nullable Object> Bikesched registerJobHandler(
      TypedJob<T> typedJob, TypedJobHandler<? super T> handler) {
    Objects.requireNonNull(handler, "handler");
    registry.requirePayloadType(typedJob);
    Class<T> payloadType = typedJob.payloadType();
    return registerJobHandler(
        typedJob.name(), job -> handler.handle(job, job.payload(payloadType)));
  }

  @Override
  public Bikesched registerJobFailureHandler(String name, JobFailureHandler handler) {
    requireValidName(name);
    Objects.requireNonNull(handler, "handler");
    if (!registry.registerFailureHandler(name, handler)) {
      throw new IllegalArgumentException(
          "A failure handler for job name '" + name + "' is already registered");
    }
    LOG.debug("bikesched node {} registered failure handler for job name {}", nodeName, name);
    return this;
  }

  @Override
  public <T extends @Nullable Object> Bikesched registerJobFailureHandler(
      TypedJob<T> typedJob, TypedJobFailureHandler<? super T> handler) {
    Objects.requireNonNull(handler, "handler");
    registry.requirePayloadType(typedJob);
    return registerJobFailureHandler(
        typedJob.name(), JobRegistry.typed(handler, typedJob.payloadType()));
  }

  @Override
  public JobBuilder<Object> newJob(String name) {
    requireValidName(name);
    return new DefaultJobBuilder<>(name, Object.class, database.jsonMapper(), jobScheduler);
  }

  @Override
  public <T extends @Nullable Object> JobBuilder<T> newJob(TypedJob<T> typedJob) {
    registry.requirePayloadType(typedJob);
    return new DefaultJobBuilder<>(
        typedJob.name(), typedJob.payloadType(), database.jsonMapper(), jobScheduler);
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
  public boolean removeRecurringJob(String name) {
    requireValidName(name);
    return jobScheduler.removeRecurringJob(name);
  }

  @Override
  public NodeEventBuilder<Object> newEvent(String eventName, String eventKey) {
    ValidationUtil.requireName(eventName, "event name");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultEventBuilder<>(eventName, eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public <T extends @Nullable Object> NodeEventBuilder<T> newEvent(
      TypedEvent<T> typedEvent, String eventKey) {
    Objects.requireNonNull(typedEvent, "typedEvent");
    ValidationUtil.requireKey(eventKey, "event key");
    return new DefaultEventBuilder<>(
        typedEvent.name(), eventKey, database.jsonMapper(), this::publish);
  }

  @Override
  public Optional<JobInfo> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    return database.jobs().findJob(jobId).map(JobInfo.class::cast);
  }

  @Override
  public List<HistoryEntry> findJobHistory(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    ensureReady();
    return database.history().findJobHistory(jobId);
  }

  @Override
  public Admin admin() {
    return admin;
  }

  @Override
  public boolean cancel(UUID jobId, @Nullable String message) {
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
      @Nullable String payload,
      @Nullable Duration eventRetention,
      @Nullable String idempotencyKey,
      @Nullable String reference,
      @Nullable Connection connection) {
    ensureReady();
    Duration retention = eventRetention != null ? eventRetention : settings.eventRetention();
    EventStore.Publication publication =
        connection == null
            ? database
                .events()
                .publishEvent(eventName, eventKey, payload, retention, idempotencyKey, reference)
            : database
                .events()
                .publishEventInTransaction(
                    connection, eventName, eventKey, payload, retention, idempotencyKey, reference);
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
      notifyPublished(eventName, reference, publication);
    }
    return publication.eventId();
  }

  /** Reports a committed publication; the listener's exceptions must not affect publishing. */
  private void notifyPublished(
      String eventName, @Nullable String reference, EventStore.Publication publication) {
    try {
      settings
          .eventListener()
          .onEventPublished(
              new EventPublication(
                  publication.eventId(),
                  eventName,
                  reference,
                  EventSource.NODE,
                  publication.received()));
    } catch (RuntimeException e) {
      LOG.warn("Event listener failed for event {}, ignoring it", publication.eventId(), e);
    }
  }

  private void removeShutdownHook() {
    Thread hook = shutdownHook;
    if (hook == null) {
      return;
    }
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException ignored) {
      // The JVM is shutting down (the hook may be the caller): the hook runs anyway, and a second
      // close waits for the first.
    }
  }

  /**
   * Stops polling, lets in-flight jobs finish (up to a timeout), releases jobs that are still
   * running back to {@code SCHEDULED}, stops the heartbeat and deregisters the node. A call while
   * another one is closing the node waits for it.
   */
  @Override
  public void close() {
    boolean first;
    UUID id;
    synchronized (lifecycle) {
      first = node.close();
      id = node.id();
    }
    if (!first) {
      awaitClosed();
      return;
    }
    try {
      closeNode(id);
    } finally {
      // Only now: a hook started meanwhile by the JVM's shutdown waits for this close.
      removeShutdownHook();
      closed.countDown();
    }
  }

  private void closeNode(@Nullable UUID id) {
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

  /**
   * Waits for the close in progress, so no caller returns while the node still drains; an interrupt
   * does not end the wait (the close is bounded by its executor timeouts) and is restored after it.
   */
  private void awaitClosed() {
    boolean interrupted = false;
    while (true) {
      try {
        closed.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
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
      // The row goes stale; the other nodes release its jobs and remove it.
      LOG.warn("Could not deregister bikesched node {} ({})", nodeName, id, e);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored") // runs until shutdown, the task logs its failures
  private void scheduleWithFixedDelay(
      ScheduledExecutorService executor, Runnable task, Duration interval) {
    long millis = interval.toMillis();
    executor.scheduleWithFixedDelay(
        () -> {
          try (var mdc = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(node.id()))) {
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
  @SuppressWarnings("FutureReturnValueIgnored") // the task logs its failures
  private void scheduleOnce(ScheduledExecutorService executor, Runnable task, Duration delay) {
    executor.schedule(
        () -> {
          try (var mdc = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(node.id()))) {
            task.run();
          }
        },
        delay.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  private static void requireValidName(String name) {
    ValidationUtil.requireName(name, "job name");
  }
}
