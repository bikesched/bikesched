// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bikesched.job.Job;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running attempt of a job and its timeout timer, shared between the job's thread, the timeout
 * thread and the handler ({@link Job#updateTimeout(Duration)}). Synchronized so the timer can never
 * interrupt the job's thread after the handler returned, i.e. while it records the outcome, and so
 * exactly one of the job's thread ({@link #finish()}) and the hard timeout ends the attempt.
 */
final class JobAttempt {

  private static final Logger LOG = LoggerFactory.getLogger(JobProcessor.class);

  /** How an attempt ended, see {@link #finish()}. */
  enum End {
    /** The handler returned (or threw) in time. */
    RETURNED,
    /** The handler returned (or threw) after the timeout interrupted it. */
    TIMED_OUT,
    /** The hard timeout abandoned the attempt before the handler returned; record nothing. */
    ABANDONED
  }

  private final Job job;
  private final Thread thread;
  private final ScheduledExecutorService timers;
  private final String nodeId;
  private final @Nullable Duration hardTimeoutGrace;
  private final Consumer<JobAttempt> onAbandoned;
  private @Nullable Duration timeout;
  private @Nullable ScheduledFuture<?> timer;
  private State state = State.RUNNING;

  /** Where the attempt is; changes only under the attempt's lock. */
  private enum State {
    /** The handler runs within its timeout (if any). */
    RUNNING,
    /** The timeout interrupted the handler, which has not returned yet. */
    TIMED_OUT,
    /** The handler returned or threw, see {@link #finish()}. */
    FINISHED,
    /** The hard timeout abandoned the attempt before the handler returned. */
    ABANDONED
  }

  /**
   * @param job the job
   * @param thread the thread running the handler
   * @param timers runs the timers
   * @param nodeId the node, for logging
   * @param hardTimeoutGrace how long after the timeout the attempt is abandoned if its handler has
   *     not returned; {@code null} to wait for the handler however long it takes
   * @param onAbandoned called (on the timer thread) once the attempt was abandoned
   */
  JobAttempt(
      Job job,
      Thread thread,
      ScheduledExecutorService timers,
      String nodeId,
      @Nullable Duration hardTimeoutGrace,
      Consumer<JobAttempt> onAbandoned) {
    this.job = job;
    this.thread = thread;
    this.timers = timers;
    this.nodeId = nodeId;
    this.hardTimeoutGrace = hardTimeoutGrace;
    this.onAbandoned = onAbandoned;
  }

  /**
   * (Re)starts the timer: the attempt times out once the given duration has passed from now,
   * replacing any earlier deadline.
   *
   * @throws IllegalStateException if the attempt already finished or timed out
   */
  synchronized void startTimeout(Duration timeout) {
    switch (state) {
      case RUNNING -> {}
      case TIMED_OUT ->
          throw new IllegalStateException(
              "Job " + job.id() + " already timed out after " + this.timeout);
      case FINISHED, ABANDONED ->
          throw new IllegalStateException("Job " + job.id() + " is no longer running");
    }
    if (timer != null) {
      timer.cancel(false);
    }
    this.timeout = timeout;
    timer = schedule(this::timeOut, timeout);
  }

  /** The timeout currently enforced, or {@code null} if none. */
  synchronized @Nullable Duration timeout() {
    return timeout;
  }

  /** The thread running the handler. */
  Thread thread() {
    return thread;
  }

  /**
   * Called by the timer: marks the attempt timed out and interrupts it, unless it finished; with a
   * hard timeout, also starts its timer.
   */
  private void timeOut() {
    Duration exceeded;
    synchronized (this) {
      if (state != State.RUNNING) {
        return;
      }
      state = State.TIMED_OUT;
      exceeded = timeout;
      thread.interrupt();
      if (hardTimeoutGrace != null) {
        timer = schedule(this::hardTimeOut, hardTimeoutGrace);
      }
    }
    try (var logContext = JobLogContext.open(nodeId, job)) {
      LOG.warn(
          "Job {} named {} exceeded its timeout of {} (attempt {} of {}), interrupting it",
          job.id(),
          job.name(),
          exceeded,
          job.attempts(),
          job.maxAttempts());
    }
  }

  /** Called by the hard timeout's timer: abandons the attempt, unless its handler returned. */
  private void hardTimeOut() {
    synchronized (this) {
      if (state != State.TIMED_OUT) {
        return;
      }
      state = State.ABANDONED;
    }
    onAbandoned.accept(this);
  }

  /**
   * Called by the job's thread once the handler returned or threw: stops the timers.
   *
   * @return how the attempt ended; {@link End#ABANDONED} if the hard timeout ended it first
   */
  synchronized End finish() {
    End end =
        switch (state) {
          case RUNNING -> End.RETURNED;
          case TIMED_OUT -> End.TIMED_OUT;
          case ABANDONED -> End.ABANDONED;
          case FINISHED ->
              throw new IllegalStateException("Attempt of job " + job.id() + " already finished");
        };
    if (end != End.ABANDONED) {
      state = State.FINISHED;
      if (timer != null) {
        timer.cancel(false);
      }
    }
    return end;
  }

  private @Nullable ScheduledFuture<?> schedule(Runnable task, Duration delay) {
    try {
      return timers.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    } catch (RejectedExecutionException e) {
      return null; // closing: the job executor's shutdown interrupts long-running jobs instead
    }
  }
}
