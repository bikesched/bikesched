package org.bikesched.internal.job;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.bikesched.BikeschedLogContext;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.util.ExecutorUtil;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Claims due jobs of the registered types and runs them, while the node polls.
 *
 * <p>Threads:
 *
 * <ul>
 *   <li>{@code bikesched-poller}: single scheduled thread claiming due jobs (skip locked) and
 *       handing them to the job executor
 *   <li>{@code bikesched-job-N}: one virtual thread per claimed job, processed by the {@link
 *       JobProcessor} (running the {@link JobHandler} registered for its type and recording the
 *       outcome)
 *   <li>{@code bikesched-timeouts}: single scheduled thread interrupting job attempts that exceed
 *       their timeout ({@link JobBuilder#timeout(Duration)}, {@link
 *       JobPollerSettings#defaultJobTimeout()}) and abandoning them after the {@link HardTimeout},
 *       if configured
 * </ul>
 *
 * <p>Concurrency limit: a semaphore holds one permit per job slot ({@link
 * JobPollerSettings#maxConcurrentJobs()}). The poller claims at most {@code min(free slots,
 * POLL_BATCH_SIZE)} jobs and skips polling while no slot is free; each job returns its slot when it
 * ends. Jobs are therefore only claimed when they can start immediately and never wait in a local
 * queue while marked {@code RUNNING} in the database.
 */
public final class JobPoller {

  /** Maximum number of jobs claimed per poll. */
  static final int POLL_BATCH_SIZE = 10;

  private static final Logger LOG = LoggerFactory.getLogger(JobPoller.class);

  private final Database database;
  private final JobPollerSettings settings;
  private final JobRegistry registry;
  private final String nodeName;
  private final Semaphore slots;
  private final ScheduledExecutorService pollerExecutor;
  private final ScheduledThreadPoolExecutor timeoutExecutor;
  private final ExecutorService jobExecutor;

  /** Set (once) by {@link #start(UUID)}, together with the processor; {@code null} until then. */
  private volatile UUID nodeId;

  private volatile JobProcessor processor;

  /**
   * Creates the poller; it polls once {@link #start(UUID) started}.
   *
   * @param database the database
   * @param settings the node's settings
   * @param registry the node's job types
   */
  public JobPoller(Database database, JobPollerSettings settings, JobRegistry registry) {
    this.database = database;
    this.settings = settings;
    this.registry = registry;
    this.nodeName = settings.nodeName();
    this.slots = new Semaphore(settings.maxConcurrentJobs());
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
   * Starts polling for the registered node; called once.
   *
   * @param id the node's id
   */
  public void start(UUID id) {
    processor =
        new JobProcessor(
            database,
            id,
            nodeName,
            registry::handler,
            registry::recurringJob,
            timeoutExecutor,
            settings.defaultJobTimeout(),
            settings.defaultEventTimeout(),
            settings.retryBackoff(),
            settings.hardTimeoutGrace() == null
                ? null
                : new HardTimeout(settings.hardTimeoutGrace(), jobExecutor, id, nodeName),
            settings.jobListener());
    nodeId = id;
    long millis = settings.pollInterval().toMillis();
    pollerExecutor.scheduleWithFixedDelay(
        () -> {
          try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, id.toString())) {
            poll();
          }
        },
        millis,
        millis,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Stops polling and lets running jobs finish (up to the shutdown timeout, then interrupts them);
   * jobs still running stay {@code RUNNING} in the database for the caller to release.
   */
  public void close() {
    ExecutorUtil.shutdownAndAwait(pollerExecutor, "poller");
    ExecutorUtil.shutdownAndAwait(jobExecutor, "job executor");
    timeoutExecutor
        .shutdownNow(); // all jobs ended (or were abandoned): no timeouts left to enforce
  }

  private void poll() {
    // Must never throw: an exception would silently cancel all future polls.
    try {
      int free = slots.availablePermits();
      if (free == 0) {
        LOG.debug("bikesched node {} ({}) is at capacity, skipping poll", nodeName, nodeId);
        return;
      }
      if (registry.types().isEmpty()) {
        // Avoids polling right after startup, before the application had a chance to register its
        // handlers.
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
      slots.acquireUninterruptibly(limit);
      List<DefaultJob> jobs = List.of();
      try {
        jobs =
            database.jobs().claimJobs(nodeId, registry.types(), limit).stream()
                .map(claimed -> new DefaultJob(database, claimed))
                .toList();
      } finally {
        slots.release(limit - jobs.size()); // unused slots; all of them if claiming failed
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

  /**
   * Runs a claimed job on its own virtual thread; the job's slot is held until it ends (or the hard
   * timeout abandons it).
   */
  private void dispatch(DefaultJob job) {
    var slot = new JobSlot(slots);
    try {
      jobExecutor.execute(
          () -> {
            try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(nodeId));
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_ID, job.id().toString());
                var _ = MDC.putCloseable(BikeschedLogContext.JOB_TYPE, job.type())) {
              processor.process(job, slot);
            } finally {
              slot.release();
            }
          });
    } catch (RejectedExecutionException e) {
      // Shutting down: the job stays RUNNING in the database and is released in close().
      slot.release();
    }
  }
}
