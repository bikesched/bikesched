// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job.claim;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.BikeschedLogContext;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.job.DefaultJob;
import org.bikesched.internal.job.DueTimes;
import org.bikesched.internal.job.HardTimeout;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.internal.job.JobRegistry;
import org.bikesched.internal.job.JobScheduler;
import org.bikesched.internal.job.JobSlot;
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
 * <p>Wake-ups: besides every {@link JobPollerSettings#pollInterval()} (the safety net), the poller
 * polls when jobs it handles become due: right away for jobs due now ({@link #dueNow}, from this
 * node's writes or from the {@link JobNotificationListener}'s notifications of other nodes), and
 * through its {@link WakeUpTimer} for jobs due later ({@link #dueAt}, from this node's writes, and
 * from the next-due query after interval polls, timer polls, (re)connects and new handlers). A
 * wake-up that finds no free slot is remembered: the next slot that frees up polls.
 *
 * <p>Concurrency limit: a semaphore holds one permit per job slot ({@link
 * JobPollerSettings#maxConcurrentJobs()}). The poller claims at most {@code min(free slots,
 * POLL_BATCH_SIZE)} jobs and skips polling while no slot is free; each job returns its slot when it
 * ends. Jobs are therefore only claimed when they can start immediately and never wait in a local
 * queue while marked {@code RUNNING} in the database.
 */
public final class JobPoller implements DueTimes {

  /** Maximum number of jobs claimed per poll. */
  static final int POLL_BATCH_SIZE = 10;

  private static final Logger LOG = LoggerFactory.getLogger(JobPoller.class);

  private final Database database;
  private final JobPollerSettings settings;
  private final JobRegistry registry;
  private final JobScheduler scheduler;
  private final String nodeName;
  private final Semaphore slots;
  private final ScheduledThreadPoolExecutor pollerExecutor;
  private final ScheduledThreadPoolExecutor timeoutExecutor;
  private final ExecutorService jobExecutor;
  private final Map<UUID, AtomicInteger> activeJobs = new ConcurrentHashMap<>();
  private final WakeUpTimer timer;

  /** Merges requests to poll now into one poll. */
  private final AtomicBoolean pollRequested = new AtomicBoolean();

  /** Set by a wake-up that found no free slot: the next released slot polls. */
  private final AtomicBoolean wakeWhenSlotFrees = new AtomicBoolean();

  /** Receives the notifications of other nodes; {@code null} if not listening. */
  private volatile JobNotificationListener listener;

  /** Set (once) by {@link #start(UUID)}, together with the processor; {@code null} until then. */
  private volatile UUID nodeId;

  private volatile JobProcessor processor;

  /**
   * Creates the poller; it polls once {@link #start(UUID) started}.
   *
   * @param database the database
   * @param settings the node's settings
   * @param registry the node's job types
   * @param scheduler stores the child jobs the claimed jobs start
   */
  public JobPoller(
      Database database, JobPollerSettings settings, JobRegistry registry, JobScheduler scheduler) {
    this.database = database;
    this.settings = settings;
    this.registry = registry;
    this.scheduler = scheduler;
    this.nodeName = settings.nodeName();
    this.slots = new Semaphore(settings.maxConcurrentJobs());
    this.pollerExecutor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("bikesched-poller").daemon().factory());
    // close() must not wait for the wake-up timer; replaced timers are removed right away.
    this.pollerExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    this.pollerExecutor.setRemoveOnCancelPolicy(true);
    this.timer = new WakeUpTimer(pollerExecutor, () -> onPollerThread(this::timerFired));
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
            registry::failureHandler,
            settings.failureHandlerAttempts(),
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
        () ->
            onPollerThread(
                () -> {
                  armForNextDue();
                  poll(false);
                }),
        millis,
        millis,
        TimeUnit.MILLISECONDS);
    registry.onNewHandler(this::wakeUpAndLookAhead);
    // The jobs that became due before this node polled, or become due within the first interval.
    wakeUpAndLookAhead();
    if (settings.listenForDueJobs()) {
      listener =
          new JobNotificationListener(
              database.jobs(),
              registry::types,
              this::pollNow,
              this::wakeUpAndLookAhead,
              settings.pollInterval(),
              nodeName,
              id);
      listener.start();
    }
  }

  /** Polls right away (merged with other requests); ignored until started and after closing. */
  public void pollNow() {
    if (nodeId == null || !pollRequested.compareAndSet(false, true)) {
      return;
    }
    submit(
        () -> {
          pollRequested.set(false);
          poll(true);
        });
  }

  @Override
  public void dueNow(Set<String> types) {
    for (String type : types) {
      if (registry.types().contains(type)) {
        pollNow();
        return;
      }
    }
  }

  @Override
  public void dueAt(String type, Instant dueAt) {
    if (nodeId == null || !registry.types().contains(type)) {
      return;
    }
    Instant now = Instant.now();
    if (!dueAt.isAfter(now)) {
      pollNow();
    } else if (!dueAt.isAfter(now.plus(settings.pollInterval()))) {
      // Later due times are found by the next-due query after an interval poll.
      submit(() -> timer.arm(dueAt));
    }
  }

  /** Polls and then looks for the jobs due within the next interval, on the poller thread. */
  private void wakeUpAndLookAhead() {
    if (nodeId == null) {
      return;
    }
    submit(
        () -> {
          armForNextDue();
          poll(true);
        });
  }

  private void timerFired() {
    LOG.debug("bikesched node {} ({}) woken up by its timer", nodeName, nodeId);
    armForNextDue();
    poll(true);
  }

  /**
   * Arms the timer for the earliest job of the node's types that becomes due within the next poll
   * interval (the next interval poll looks further). Poller thread only, always right before a
   * poll: the query looks at jobs due after its start, the poll claims those due before its own, so
   * no job becomes due unseen in between.
   */
  private void armForNextDue() {
    if (registry.types().isEmpty()) {
      return;
    }
    try {
      database
          .jobs()
          .nextDueIn(registry.types(), settings.pollInterval())
          .ifPresent(delay -> timer.arm(Instant.now().plus(delay)));
    } catch (RuntimeException e) {
      LOG.warn("Looking up the next due job failed on bikesched node {} ({})", nodeName, nodeId, e);
    }
  }

  /** Runs the task on the poller thread with the node id in the MDC; nothing after closing. */
  private void submit(Runnable task) {
    try {
      pollerExecutor.execute(() -> onPollerThread(task));
    } catch (RejectedExecutionException e) {
      // closing
    }
  }

  private void onPollerThread(Runnable task) {
    try (var _ = MDC.putCloseable(BikeschedLogContext.NODE_ID, String.valueOf(nodeId))) {
      task.run();
    } catch (RuntimeException e) {
      // Must never throw: an exception would cancel the interval polls.
      LOG.warn("Waking up bikesched node {} ({}) failed", nodeName, nodeId, e);
    }
  }

  /** Returns a stable snapshot of jobs whose attempts still run in memory. */
  public Set<UUID> activeJobIds() {
    return Set.copyOf(activeJobs.keySet());
  }

  private void trackActiveJob(UUID jobId) {
    activeJobs.computeIfAbsent(jobId, _ -> new AtomicInteger()).incrementAndGet();
  }

  /** A job's slot was released: polls if a wake-up found no free slot. */
  private void released(UUID jobId) {
    untrackActiveJob(jobId);
    if (wakeWhenSlotFrees.compareAndSet(true, false)) {
      pollNow();
    }
  }

  private void untrackActiveJob(UUID jobId) {
    activeJobs.computeIfPresent(
        jobId,
        (_, count) -> {
          count.decrementAndGet();
          return count.get() == 0 ? null : count;
        });
  }

  /**
   * Stops polling and lets running jobs finish (up to the shutdown timeout, then interrupts them);
   * jobs still running stay {@code RUNNING} in the database for the caller to release.
   */
  public void close() {
    JobNotificationListener listening = listener;
    if (listening != null) {
      listening.close();
    }
    ExecutorUtil.shutdownAndAwait(pollerExecutor, "poller");
    ExecutorUtil.shutdownAndAwait(jobExecutor, "job executor");
    timeoutExecutor
        .shutdownNow(); // all jobs ended (or were abandoned): no timeouts left to enforce
  }

  /**
   * Claims due jobs until a poll claims less than it could.
   *
   * @param wokenUp whether a wake-up (not the interval) triggered it: if it finds no free slot, the
   *     next released slot polls
   */
  private void poll(boolean wokenUp) {
    while (pollOnce(wokenUp)) {
      // A full batch can mean more due work is waiting while slots remain free. Poll immediately
      // instead of waiting for the next interval; the next partial batch resumes the normal delay.
    }
  }

  private boolean pollOnce(boolean wokenUp) {
    // Must never throw: an exception would silently cancel all future polls.
    try {
      int free = slots.availablePermits();
      if (free == 0) {
        if (wokenUp) {
          wakeWhenSlotFrees.set(true);
        }
        LOG.debug("bikesched node {} ({}) is at capacity, skipping poll", nodeName, nodeId);
        return false;
      }
      if (registry.types().isEmpty()) {
        // Avoids polling right after startup, before the application had a chance to register its
        // handlers.
        LOG.debug(
            "bikesched node {} ({}) has no job handlers registered, skipping poll",
            nodeName,
            nodeId);
        return false;
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
                .map(
                    claimed ->
                        new DefaultJob(
                            database,
                            claimed,
                            settings.eventRetention(),
                            settings.failureHandlerAttempts(),
                            scheduler,
                            settings.eventListener()))
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
      return jobs.size() == limit;
    } catch (RuntimeException e) {
      LOG.warn("Polling for jobs failed on bikesched node {} ({})", nodeName, nodeId, e);
      return false;
    }
  }

  /**
   * Runs a claimed job on its own virtual thread; the job's slot is held until it ends (or the hard
   * timeout abandons it).
   */
  private void dispatch(DefaultJob job) {
    trackActiveJob(job.id());
    var slot = new JobSlot(slots, () -> released(job.id()));
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
