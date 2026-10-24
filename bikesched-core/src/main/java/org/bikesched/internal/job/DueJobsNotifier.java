// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bikesched.internal.database.DueJobs;
import org.bikesched.internal.database.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the nodes about the jobs this node's committed writes made due, see {@link DueJobs}:
 *
 * <ul>
 *   <li>due now: this node's {@link DueTimes} right away, and all listening nodes through one
 *       batched notification ({@link JobStore#notifyDue}) per {@code notifyInterval}: the first
 *       name of an empty batch schedules the flush on the {@code bikesched-notifier} thread, so an
 *       idle node sends nothing and a busy one at most one notifying commit per interval
 *   <li>due later: only this node's {@link DueTimes} (the other nodes find such jobs with their
 *       next-due query)
 * </ul>
 *
 * <p>A failed flush drops its batch (the jobs start on the next interval poll) and logs one warning
 * per failure streak. {@link #close()} flushes once more.
 */
public final class DueJobsNotifier implements DueJobs, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DueJobsNotifier.class);

  private final JobStore store;
  private final Duration notifyInterval;
  private final boolean notifying;
  private final DueTimes local;
  private final String nodeName;
  private final Set<String> batch = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean flushScheduled = new AtomicBoolean();
  private final ScheduledThreadPoolExecutor executor;

  /** Whether the last flush failed: warns once per failure streak. */
  private volatile boolean failing;

  /**
   * @param store sends the notifications
   * @param notifyInterval the batching window
   * @param notifying whether to notify the other nodes; {@code false}: only this node's {@link
   *     DueTimes} are told
   * @param local this node's wake-ups
   * @param nodeName the node's name, for logging
   */
  public DueJobsNotifier(
      JobStore store, Duration notifyInterval, boolean notifying, DueTimes local, String nodeName) {
    this.store = store;
    this.notifyInterval = notifyInterval;
    this.notifying = notifying;
    this.local = local;
    this.nodeName = nodeName;
    this.executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("bikesched-notifier").daemon().factory());
    // close() flushes itself instead of waiting for a scheduled flush.
    this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // flush() never throws
  public void dueNow(Set<String> names) {
    local.dueNow(names);
    if (!notifying) {
      return;
    }
    batch.addAll(names);
    if (flushScheduled.compareAndSet(false, true)) {
      try {
        executor.schedule(this::flush, notifyInterval.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        // closing: close() flushes the batch
      }
    }
  }

  @Override
  public void dueAt(String name, Instant dueAt) {
    local.dueAt(name, dueAt);
  }

  @Override
  public boolean isNotifying() {
    return notifying;
  }

  /** Sends the batch; never throws (it runs on the notifier thread and in {@link #close()}). */
  private void flush() {
    // Cleared before draining: a name added from now on is either drained below or schedules the
    // next flush.
    flushScheduled.set(false);
    List<String> names = new ArrayList<>();
    for (Iterator<String> pending = batch.iterator(); pending.hasNext(); ) {
      names.add(pending.next());
      pending.remove();
    }
    if (names.isEmpty()) {
      return;
    }
    try {
      store.notifyDue(names);
      LOG.debug("bikesched node {} notified due jobs named {}", nodeName, names);
      if (failing) {
        failing = false;
        LOG.info("bikesched node {} notifies due jobs again", nodeName);
      }
    } catch (RuntimeException e) {
      if (!failing) {
        failing = true;
        LOG.warn(
            "bikesched node {} could not notify due jobs named {}; other nodes start them on"
                + " their next poll",
            nodeName,
            names,
            e);
      }
    }
  }

  /** Stops the notifier thread and sends what is left. */
  @Override
  public void close() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        LOG.warn("bikesched node {}: the notifier did not stop in time", nodeName);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    flush();
  }
}
