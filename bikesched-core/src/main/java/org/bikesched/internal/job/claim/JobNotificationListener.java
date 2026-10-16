// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job.claim;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bikesched.BikeschedLogContext;
import org.bikesched.internal.database.DueJobsListening;
import org.bikesched.internal.database.JobStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Receives the notifications of due jobs ({@link JobStore#notifyDue}, sent by every node's {@code
 * DueJobsNotifier}) on the {@code bikesched-listener} thread, and wakes the poller for the types
 * the node handles.
 *
 * <ul>
 *   <li>Holds one connection of the data source for its lifetime, borrowed on its own thread:
 *       {@link #start()} never waits for it, also with an exhausted pool.
 *   <li>After each (re)connect it wakes the poller with the next-due query, for what it missed, and
 *       checks that notifications arrive: it sends itself a random token and warns if it does not
 *       arrive within {@link #SELF_CHECK_TIMEOUT} (e.g. behind PgBouncer in transaction mode).
 *   <li>If the connection fails, it warns and reconnects after the poll interval.
 *   <li>If the data source's connections are no PostgreSQL JDBC connections, it warns once and
 *       stops: the node works without notifications.
 * </ul>
 */
final class JobNotificationListener {

  /** How long the self-check waits for its own notification. */
  static final Duration SELF_CHECK_TIMEOUT = Duration.ofSeconds(1);

  /** How long one wait for notifications lasts at most; {@link #close()} ends it right away. */
  private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

  private static final String SELF_CHECK_PREFIX = "bikesched-self-check-";

  private static final Logger LOG = LoggerFactory.getLogger(JobNotificationListener.class);

  private final JobStore store;
  private final Supplier<Set<String>> types;
  private final Runnable wakeUp;
  private final Runnable connected;
  private final Duration retryDelay;
  private final String nodeName;
  private final UUID nodeId;
  private final CountDownLatch closed = new CountDownLatch(1);
  private volatile @Nullable DueJobsListening listening;
  private volatile @Nullable Thread thread;

  /**
   * @param store listens and sends the self-check
   * @param types the job types the node handles
   * @param wakeUp polls now, for a notification of a handled type
   * @param connected polls now and runs the next-due query, after each (re)connect
   * @param retryDelay how long to wait before reconnecting
   * @param nodeName the node's name, for logging
   * @param nodeId the node's id, for logging
   */
  JobNotificationListener(
      JobStore store,
      Supplier<Set<String>> types,
      Runnable wakeUp,
      Runnable connected,
      Duration retryDelay,
      String nodeName,
      UUID nodeId) {
    this.store = store;
    this.types = types;
    this.wakeUp = wakeUp;
    this.connected = connected;
    this.retryDelay = retryDelay;
    this.nodeName = nodeName;
    this.nodeId = nodeId;
  }

  /** Starts listening on the {@code bikesched-listener} thread; returns right away. */
  void start() {
    thread =
        Thread.ofPlatform()
            .name("bikesched-listener")
            .daemon()
            .start(
                () -> {
                  try (var mdc = MDC.putCloseable(BikeschedLogContext.NODE_ID, nodeId.toString())) {
                    run();
                  }
                });
  }

  /**
   * Stops listening: closes the connection, which ends a blocked wait, and waits for the thread.
   */
  void close() {
    closed.countDown();
    DueJobsListening current = listening;
    if (current != null) {
      current.close();
    }
    Thread running = thread;
    if (running != null) {
      try {
        if (!running.join(Duration.ofSeconds(10))) {
          LOG.warn("The listener of bikesched node {} ({}) did not stop in time", nodeName, nodeId);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private boolean isClosed() {
    return closed.getCount() == 0;
  }

  private void run() {
    boolean reconnect = false;
    while (!isClosed()) {
      DueJobsListening current;
      try {
        current = store.listen();
        listening = current;
      } catch (UnsupportedOperationException e) {
        LOG.warn(
            "bikesched node {} ({}) cannot listen for due jobs, it polls every {} instead: {}",
            nodeName,
            nodeId,
            retryDelay,
            e.getMessage());
        return;
      } catch (RuntimeException e) {
        if (!isClosed()) {
          LOG.warn(
              "bikesched node {} ({}) could not listen for due jobs, retrying in {}",
              nodeName,
              nodeId,
              retryDelay,
              e);
        }
        awaitRetry();
        continue;
      }
      try {
        receive(current, reconnect);
      } catch (RuntimeException e) {
        if (!isClosed()) {
          LOG.warn(
              "bikesched node {} ({}) stopped receiving notifications of due jobs, reconnecting in"
                  + " {}",
              nodeName,
              nodeId,
              retryDelay,
              e);
          awaitRetry();
        }
      } finally {
        current.close();
      }
      reconnect = true;
    }
  }

  /** Receives notifications on the current connection until it fails or the listener closes. */
  private void receive(DueJobsListening listening, boolean reconnected) {
    if (isClosed()) {
      return; // close() may have missed the new connection
    }
    LOG.info(
        "bikesched node {} ({}) {} for due jobs",
        nodeName,
        nodeId,
        reconnected ? "reconnected, listening again" : "listening");
    connected.run();
    String token = SELF_CHECK_PREFIX + UUID.randomUUID();
    store.notifyDue(List.of(token));
    Instant checkUntil = Instant.now().plus(SELF_CHECK_TIMEOUT);
    while (!isClosed()) {
      Duration timeout =
          checkUntil == null
              ? RECEIVE_TIMEOUT
              : Duration.between(Instant.now(), checkUntil).plusMillis(1);
      List<String> payloads = listening.receive(timeout);
      boolean handled = false;
      for (String payload : payloads) {
        if (payload.equals(token)) {
          checkUntil = null;
          LOG.debug("bikesched node {} ({}) received its self-check", nodeName, nodeId);
        } else if (types.get().contains(payload)) {
          handled = true;
        }
      }
      if (!payloads.isEmpty()) {
        LOG.debug(
            "bikesched node {} ({}) received notifications {}{}",
            nodeName,
            nodeId,
            payloads,
            handled ? ", polling" : "");
      }
      if (handled) {
        wakeUp.run();
      }
      if (checkUntil != null && !Instant.now().isBefore(checkUntil)) {
        checkUntil = null;
        LOG.warn(
            "bikesched node {} ({}) listens for due jobs but did not receive its own notification"
                + " within {}; if connections pass through a pooler like PgBouncer in transaction"
                + " mode, turn off listenForDueJobs and lower the poll interval",
            nodeName,
            nodeId,
            SELF_CHECK_TIMEOUT);
      }
    }
  }

  private void awaitRetry() {
    try {
      closed.await(retryDelay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closed.countDown();
    }
  }
}
