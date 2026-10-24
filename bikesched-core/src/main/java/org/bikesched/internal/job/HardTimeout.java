// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bikesched.BikeschedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hard timeout of attempts whose handler ignores the interrupt of its timeout (e.g. stuck in
 * non-interruptible code): once the grace period after the timeout has passed without the handler
 * returning, the attempt is abandoned. Its failure is recorded as for a timeout (retries and
 * compensation apply as usual), its slot is freed and the thread's stack trace logged once.
 *
 * <p>The thread itself cannot be stopped and keeps running. Recording the failure ends the claim,
 * so the thread's later writes are rejected by the claim's lock token; once its handler returns,
 * the attempt records nothing ({@link #returned}). Abandoned threads still running are counted.
 */
public final class HardTimeout {

  private static final Logger LOG = LoggerFactory.getLogger(HardTimeout.class);

  private final Duration grace;
  private final Executor recorder;
  private final UUID nodeId;
  private final String nodeName;
  private final AtomicInteger abandonedThreads = new AtomicInteger();

  /**
   * @param grace how long after its timeout an attempt is abandoned if its handler has not returned
   * @param recorder runs the recording of abandoned attempts' failures (not on the timer thread,
   *     whose database access would delay other timeouts)
   * @param nodeId the node, for logging
   * @param nodeName its name, for logging
   */
  public HardTimeout(Duration grace, Executor recorder, UUID nodeId, String nodeName) {
    this.grace = grace;
    this.recorder = recorder;
    this.nodeId = nodeId;
    this.nodeName = nodeName;
  }

  /** How long after its timeout an attempt is abandoned. */
  Duration grace() {
    return grace;
  }

  /**
   * Abandons an attempt whose handler did not return within the grace period after its timeout;
   * called once by the attempt's timer.
   *
   * @param job the job
   * @param attempt the attempt, already marked abandoned
   * @param slot the job's slot, freed now
   * @param recordFailure records the attempt as failed with the given timeout error
   */
  void abandon(
      DefaultJob job, JobAttempt attempt, JobSlot slot, Consumer<Exception> recordFailure) {
    int running = abandonedThreads.incrementAndGet();
    try (var logContext = JobLogContext.open(nodeId, job)) {
      LOG.warn(
          "Job {} named {} did not stop within {} after its timeout of {} (attempt {} of {}),"
              + " abandoning thread {} on bikesched node {} ({} abandoned thread(s) still"
              + " running); it is stuck at:{}",
          job.id(),
          job.name(),
          grace,
          attempt.timeout(),
          job.attempts(),
          job.maxAttempts(),
          attempt.thread().getName(),
          nodeName,
          running,
          stackTrace(attempt.thread()));
    }
    slot.release();
    var failure =
        new BikeschedException(
            "Job timed out after "
                + attempt.timeout()
                + " and did not stop within the hard timeout grace of "
                + grace);
    try {
      recorder.execute(
          () -> {
            try (var logContext = JobLogContext.open(nodeId, job)) {
              recordFailure.accept(failure);
            }
          });
    } catch (RejectedExecutionException e) {
      // Closing: the job is still RUNNING in the database and released in close().
      LOG.warn("Abandoned attempt of job {} not recorded, bikesched is closing", job.id());
    }
  }

  /**
   * Called by an abandoned attempt's thread once its handler finally returned: its outcome is
   * discarded, the attempt's failure was recorded when it was abandoned.
   *
   * @param job the job
   * @param failure the exception the handler ended with, {@code null} if it returned normally
   * @return a description of the outcome, for logging
   */
  String returned(DefaultJob job, @Nullable Exception failure) {
    int running = abandonedThreads.decrementAndGet();
    LOG.info(
        "Abandoned attempt {} of job {} named {} returned after all{}; its outcome is discarded"
            + " ({} abandoned thread(s) still running)",
        job.attempts(),
        job.id(),
        job.name(),
        failure == null ? "" : " with " + failure,
        running);
    return "abandoned after the hard timeout, outcome not recorded";
  }

  /** The number of abandoned threads that have not returned yet. */
  int abandonedThreads() {
    return abandonedThreads.get();
  }

  private static String stackTrace(Thread thread) {
    return Arrays.stream(thread.getStackTrace())
        .map(element -> System.lineSeparator() + "\tat " + element)
        .collect(Collectors.joining());
  }
}
