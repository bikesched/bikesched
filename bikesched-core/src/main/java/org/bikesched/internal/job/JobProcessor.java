// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import org.bikesched.BikeschedException;
import org.bikesched.JobListener;
import org.bikesched.RetryBackoff;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.NewJob;
import org.bikesched.job.Job;
import org.bikesched.job.JobAttemptOutcome;
import org.bikesched.job.JobAttemptResult;
import org.bikesched.job.JobClaim;
import org.bikesched.job.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes the jobs claimed by a node, one call of {@link #process(DefaultJob)} per job on the
 * job's own thread: runs the {@link JobHandler} registered for the job's type within the job's
 * timeout and records the outcome in the database.
 *
 * <p>Outcomes, by precedence when a handler does several things:
 *
 * <ol>
 *   <li>{@link Job#cancel(String) cancelled}: {@code CANCELLED}, no retry
 *   <li>{@link Job#fail(String) failed explicitly}: retried with the handler's retry settings
 *   <li>{@link Job#waitForEvent(String, String, String) waiting for an event}: {@code WAITING}
 *   <li>timed out, or threw: retried after the node's {@link RetryBackoff} while attempts remain,
 *       otherwise {@code FAILED}
 *   <li>returned normally: {@code SUCCEEDED}
 * </ol>
 *
 * <p>A job whose cancellation was requested is cancelled without running its handler. A cancelled
 * or permanently failed job with completed compensable steps becomes {@code COMPENSATING} instead
 * of ending; when claimed again, its handler is replayed in compensation mode and the registered
 * compensations run, ending the job (or retrying the compensation, see {@link Job#newStep(String,
 * Class)}).
 *
 * <p>Each processed claim is reported to the {@link JobListener}: {@code onJobClaimed} before,
 * {@code onAttemptEnded} once the outcome is recorded.
 *
 * <p>Thread-safe; holds no per-job state.
 */
public final class JobProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(JobProcessor.class);

  // Outcomes of processing a job that could not be recorded as such.
  private static final Outcome NOT_RUNNING =
      new Outcome(
          JobAttemptOutcome.NOT_RECORDED,
          "not recorded, the job was no longer running in this attempt's claim");
  private static final Outcome NOT_RECORDED =
      new Outcome(JobAttemptOutcome.NOT_RECORDED, "not recorded, see the warning above");

  /**
   * The recorded outcome of an attempt.
   *
   * @param kind what was recorded; {@code null} for an abandoned attempt whose thread returned,
   *     which was reported when it was abandoned
   * @param description a description, for the debug log
   */
  private record Outcome(JobAttemptOutcome kind, String description) {}

  /** A claim being processed, started at {@code startNanos} ({@link System#nanoTime()}). */
  private record Started(JobClaim claim, long startNanos) {

    Duration elapsed() {
      return Duration.ofNanos(System.nanoTime() - startNanos);
    }
  }

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Function<String, JobHandler> handlers;
  private final Function<String, RecurringJob> recurringJobs;
  private final ScheduledExecutorService timeoutExecutor;
  private final Duration defaultJobTimeout;
  private final Duration defaultEventTimeout;
  private final RetryBackoff retryBackoff;
  private final HardTimeout hardTimeout;
  private final JobListener listener;

  /**
   * @param database the database
   * @param nodeId the node the jobs were claimed by
   * @param nodeName its name, for logging
   * @param handlers looks up the handler of a job type, {@code null} if none is registered
   * @param recurringJobs looks up the recurring job registered on this node for a job type, {@code
   *     null} if none
   * @param timeoutExecutor runs the timers interrupting attempts that exceed their timeout
   * @param defaultJobTimeout timeout of jobs without one of their own, {@code null} for none
   * @param defaultEventTimeout timeout of waits for events without one of their own, {@code null}
   *     for none
   * @param retryBackoff delay of the retry after a failed attempt
   * @param hardTimeout abandons attempts that ignore their timeout, {@code null} for none
   * @param listener observes the processed jobs
   */
  public JobProcessor(
      Database database,
      UUID nodeId,
      String nodeName,
      Function<String, JobHandler> handlers,
      Function<String, RecurringJob> recurringJobs,
      ScheduledExecutorService timeoutExecutor,
      Duration defaultJobTimeout,
      Duration defaultEventTimeout,
      RetryBackoff retryBackoff,
      HardTimeout hardTimeout,
      JobListener listener) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.recurringJobs = Objects.requireNonNull(recurringJobs, "recurringJobs");
    this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
    this.defaultJobTimeout = defaultJobTimeout;
    this.defaultEventTimeout = defaultEventTimeout;
    this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
    this.hardTimeout = hardTimeout;
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /**
   * Starts an attempt of the job on the current thread: its timeout (the job's own or the node
   * default, if any) and, with a hard timeout, abandoning it if it ignores the timeout.
   */
  private JobAttempt startAttempt(DefaultJob job, JobSlot slot, Started started) {
    var attempt =
        new JobAttempt(
            job,
            Thread.currentThread(),
            timeoutExecutor,
            nodeId.toString(),
            hardTimeout == null ? null : hardTimeout.grace(),
            abandoned ->
                hardTimeout.abandon(
                    job, abandoned, slot, failure -> recordAbandoned(job, failure, started)));
    Duration timeout = job.timeout().orElse(defaultJobTimeout);
    if (timeout != null) {
      attempt.startTimeout(timeout);
    }
    job.attach(attempt);
    job.defaultEventTimeout(defaultEventTimeout);
    return attempt;
  }

  /**
   * Records an attempt abandoned by the hard timeout as failed, like a timed-out attempt (on the
   * hard timeout's recorder thread, while the handler's thread may still run).
   */
  private void recordAbandoned(DefaultJob job, Exception failure, Started started) {
    Outcome outcome =
        job.isCompensating()
            ? recordCompensationFailure(job, null, failure)
            : recordFailure(job, failure);
    notifyListener(
        job,
        () ->
            listener.onAttemptEnded(
                new JobAttemptResult(started.claim(), outcome.kind(), started.elapsed(), true)));
    LOG.debug("Recorded the abandoned attempt of job {}: {}", job.id(), outcome.description());
  }

  /**
   * Ends an attempt whose job was released while it ran (a step write found it no longer running in
   * the attempt's claim, see {@link DefaultJob#isLockLost()}): records nothing, the job belongs to
   * whichever claim comes next.
   */
  private Outcome abandon(DefaultJob job, Exception failure) {
    LOG.warn(
        "Job {} of type {} is no longer running on bikesched node {} ({}): it was released, e.g."
            + " after missed heartbeats, and may run on another node; abandoning attempt {}"
            + " without recording it",
        job.id(),
        job.type(),
        nodeName,
        nodeId,
        job.attempts(),
        failure);
    return new Outcome(JobAttemptOutcome.NOT_RECORDED, "abandoned, the job was released");
  }

  /**
   * Runs the job and logs the recorded outcome.
   *
   * @param job the claimed job
   * @param slot the job's slot; released here only if the hard timeout abandons the attempt, the
   *     caller releases it afterwards in any case
   */
  public void process(DefaultJob job, JobSlot slot) {
    var started = new Started(claim(job), System.nanoTime());
    notifyListener(job, () -> listener.onJobClaimed(started.claim()));
    Outcome outcome = NOT_RECORDED;
    try {
      outcome = runAndRecord(job, slot, started);
    } finally {
      Duration elapsed = started.elapsed();
      if (outcome.kind() == null) {
        notifyListener(job, () -> listener.onAbandonedAttemptReturned(started.claim()));
      } else {
        var result = new JobAttemptResult(started.claim(), outcome.kind(), elapsed, false);
        notifyListener(job, () -> listener.onAttemptEnded(result));
      }
      LOG.debug(
          "Processed job {} of type {} (attempt {}) in {} ms: {}",
          job.id(),
          job.type(),
          job.attempts(),
          elapsed.toMillis(),
          outcome.description());
    }
  }

  private static JobClaim claim(DefaultJob job) {
    Duration delay = Duration.between(job.scheduledAt(), Instant.now());
    return new JobClaim(
        job.id(),
        job.type(),
        job.isCompensating() ? job.compensationAttempts() : job.attempts(),
        job.isCompensating(),
        delay.isNegative() ? Duration.ZERO : delay);
  }

  /** Calls the listener; its exceptions must not affect processing. */
  private static void notifyListener(DefaultJob job, Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      LOG.warn("Job listener failed for job {}, ignoring it", job.id(), e);
    }
  }

  /**
   * Runs the job's handler (verified here because claiming is not filtered by job type) and records
   * the outcome.
   *
   * @return the recorded outcome
   */
  private Outcome runAndRecord(DefaultJob job, JobSlot slot, Started started) {
    if (job.isCompensating()) {
      return compensate(job, slot, started);
    }
    if (job.cancelRequested()) {
      return recordCancellation(job, job.cancelRequestMessage(), false);
    }
    JobHandler handler = handlers.apply(job.type());
    if (handler == null) {
      LOG.error(
          "No handler registered for job type {} on bikesched node {} ({}), cannot run job {}",
          job.type(),
          nodeName,
          nodeId,
          job.id());
      return recordFailure(
          job,
          new BikeschedException(
              "No handler registered for job type '"
                  + job.type()
                  + "' on bikesched node "
                  + nodeName));
    }
    JobAttempt attempt = startAttempt(job, slot, started);
    Exception failure = null;
    try {
      LOG.debug(
          "Executing job {} of type {} (attempt {} of {}) on bikesched node {} ({})",
          job.id(),
          job.type(),
          job.attempts(),
          job.maxAttempts(),
          nodeName,
          nodeId);
      handler.handle(job);
    } catch (JobSuspended e) {
      // Control flow: the job waits for an event (job.suspension() is set), handled below.
    } catch (Exception e) {
      failure = e;
    }
    JobAttempt.End end = attempt.finish(); // no interrupt can arrive after this
    // Clear a pending interrupt (timeout, shutdown or the handler's own): this thread still has
    // to record the outcome, and blocking I/O of an interrupted virtual thread fails.
    Thread.interrupted();
    if (end == JobAttempt.End.ABANDONED) {
      return new Outcome(null, hardTimeout.returned(job, failure));
    }
    if (job.isLockLost()) {
      return abandon(job, failure);
    }

    if (job.isCancelled()) {
      if (failure != null) {
        // The handler's decision to cancel wins over the failure: no retry.
        LOG.warn(
            "Job {} of type {} was cancelled, ignoring the exception thrown by its handler afterwards",
            job.id(),
            job.type(),
            failure);
      }
      return recordCancellation(job, job.cancelMessage(), true);
    } else if (job.failure() != null) {
      if (failure != null) {
        // The handler's explicit failure (and its retry settings) wins over the exception.
        LOG.warn(
            "Job {} of type {} was failed by its handler, ignoring the exception thrown afterwards",
            job.id(),
            job.type(),
            failure);
      }
      DefaultJobFailure explicit = job.failure();
      Integer remainingAttempts = explicit.remainingAttempts();
      return recordFailure(
          job,
          explicit.message() == null ? "Job failed by its handler" : explicit.message(),
          null,
          remainingAttempts == null ? null : job.attempts() + remainingAttempts,
          explicit.retryDelay());
    } else if (job.suspension() != null) {
      if (failure != null) {
        // The handler caught the suspension and threw afterwards: the wait wins.
        LOG.warn(
            "Job {} of type {} waits for an event, ignoring the exception thrown by its handler"
                + " afterwards",
            job.id(),
            job.type(),
            failure);
      }
      return recordSuspension(job, job.suspension());
    } else if (end == JobAttempt.End.TIMED_OUT) {
      return recordFailure(
          job, new BikeschedException("Job timed out after " + attempt.timeout(), failure));
    } else if (failure != null) {
      return recordFailure(job, failure);
    } else {
      try {
        NewJob next = nextOccurrence(job);
        var completed = database.jobs().completeJob(job.id(), job.lock(), next);
        if (completed.isEmpty()) {
          LOG.warn(
              "Job {} was no longer running in this attempt's claim when completing it", job.id());
          return NOT_RUNNING;
        }
        return new Outcome(
            JobAttemptOutcome.SUCCEEDED,
            "SUCCEEDED" + nextOccurrenceOutcome(next, completed.get()));
      } catch (RuntimeException e) {
        LOG.warn("Completing job {} failed", job.id(), e);
        return NOT_RECORDED;
      }
    }
  }

  private Outcome recordSuspension(DefaultJob job, JobSuspended.Suspension suspension) {
    String event = "event " + suspension.eventName() + "/" + suspension.eventKey();
    try {
      var suspended =
          database
              .events()
              .suspendJob(
                  job.id(),
                  job.lock(),
                  suspension.stepName(),
                  suspension.eventName(),
                  suspension.eventKey());
      if (suspended.isEmpty()) {
        LOG.warn(
            "Job {} of type {} waits for {} but was no longer running in this attempt's claim",
            job.id(),
            job.type(),
            event);
        return NOT_RUNNING;
      }
      if (!suspended.get().waiting()) {
        return new Outcome(
            JobAttemptOutcome.WAITING,
            "RESCHEDULED, " + event + " was published while suspending the job");
      }
      Instant waitUntil = suspended.get().waitUntil();
      return new Outcome(
          JobAttemptOutcome.WAITING,
          "WAITING for "
              + event
              + " in step "
              + suspension.stepName()
              + (waitUntil == null ? "" : " until " + waitUntil));
    } catch (RuntimeException e) {
      LOG.warn("Suspending job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  /**
   * Records the cancellation of a job: by its handler ({@code byHandler}), or on request right
   * after claiming it, without running the handler (which does not use up an attempt).
   */
  private Outcome recordCancellation(DefaultJob job, String message, boolean byHandler) {
    try {
      NewJob next = nextOccurrence(job);
      var cancelled = database.jobs().cancelJob(job.id(), job.lock(), message, byHandler, next);
      if (cancelled.isPresent()) {
        boolean compensating = cancelled.get().compensating();
        LOG.info(
            "Job {} of type {} cancelled {}{}{}",
            job.id(),
            job.type(),
            byHandler
                ? "by its handler (attempt " + job.attempts() + " of " + job.maxAttempts() + ")"
                : "on request",
            message == null ? "" : ": " + message,
            compensating ? ", compensating its steps" : "");
        return compensating
            ? new Outcome(JobAttemptOutcome.COMPENSATING, "COMPENSATING after cancellation")
            : new Outcome(
                JobAttemptOutcome.CANCELLED,
                "CANCELLED"
                    + nextOccurrenceOutcome(next, cancelled.get().nextOccurrenceScheduled()));
      } else {
        LOG.warn(
            "Job {} of type {} was cancelled but no longer running in this attempt's claim",
            job.id(),
            job.type());
        return NOT_RUNNING;
      }
    } catch (RuntimeException e) {
      LOG.warn("Recording cancellation of job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  private Outcome recordFailure(DefaultJob job, Exception failure) {
    return recordFailure(job, failure.toString(), failure, null, null);
  }

  /**
   * Compensates a job: replays its handler in compensation mode, which registers the compensations
   * of its completed steps until the first step that has not completed, then runs them, latest step
   * first, and records the outcome.
   */
  private Outcome compensate(DefaultJob job, JobSlot slot, Started started) {
    JobHandler handler = handlers.apply(job.type());
    if (handler == null) {
      LOG.error(
          "No handler registered for job type {} on bikesched node {} ({}), cannot compensate job"
              + " {}",
          job.type(),
          nodeName,
          nodeId,
          job.id());
      return recordCompensationFailure(
          job,
          null,
          new BikeschedException(
              "No handler registered for job type '"
                  + job.type()
                  + "' on bikesched node "
                  + nodeName));
    }
    JobAttempt attempt = startAttempt(job, slot, started);
    String compensatingStep = null;
    Exception failure = null;
    int compensated = 0;
    try {
      LOG.debug(
          "Compensating job {} of type {} (compensation attempt {} of {}) on bikesched node {} ({})",
          job.id(),
          job.type(),
          job.compensationAttempts(),
          job.maxAttempts(),
          nodeName,
          nodeId);
      try {
        handler.handle(job);
      } catch (CompensationReplayEnded e) {
        // The replay reached a step that has not completed: nothing to compensate after it.
      } catch (Exception e) {
        LOG.debug(
            "Handler of job {} threw while replaying it for compensation, compensating the steps"
                + " completed before: {}",
            job.id(),
            e.toString());
      }
      for (DefaultJob.PendingCompensation compensation : job.compensations()) {
        compensatingStep = compensation.stepName();
        job.compensate(compensation);
        compensated++;
      }
      compensatingStep = null;
    } catch (Exception e) {
      failure = e;
    }
    JobAttempt.End end = attempt.finish(); // no interrupt can arrive after this
    Thread.interrupted(); // see runAndRecord
    if (end == JobAttempt.End.ABANDONED) {
      return new Outcome(null, hardTimeout.returned(job, failure));
    }
    if (job.isLockLost()) {
      return abandon(job, failure);
    }
    if (failure != null) {
      return recordCompensationFailure(
          job,
          compensatingStep,
          end == JobAttempt.End.TIMED_OUT
              ? new BikeschedException("Compensation timed out after " + attempt.timeout(), failure)
              : failure);
    }
    return recordCompensated(job, compensated);
  }

  private Outcome recordCompensated(DefaultJob job, int compensated) {
    try {
      NewJob next = nextOccurrence(job);
      var ended = database.jobs().completeCompensation(job.id(), job.lock(), next);
      if (ended.isEmpty()) {
        LOG.warn(
            "Job {} was no longer compensating in this attempt's claim when completing it",
            job.id());
        return NOT_RUNNING;
      }
      LOG.info(
          "Job {} of type {} compensated {} step(s), it is {}",
          job.id(),
          job.type(),
          compensated,
          ended.get().state());
      return new Outcome(
          JobAttemptOutcome.COMPENSATED,
          "COMPENSATED "
              + compensated
              + " step(s), "
              + ended.get().state()
              + nextOccurrenceOutcome(next, ended.get().nextOccurrenceScheduled()));
    } catch (RuntimeException e) {
      LOG.warn("Completing the compensation of job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  /** Records a failed compensation attempt; {@code step} is the failed compensation, if any. */
  private Outcome recordCompensationFailure(DefaultJob job, String step, Exception failure) {
    String error =
        step == null ? failure.toString() : "Compensating step '" + step + "' failed: " + failure;
    Duration retryAfter = backoffDelay(job, job.compensationAttempts());
    try {
      NewJob next = nextOccurrence(job);
      var failed = database.jobs().failCompensation(job.id(), job.lock(), error, retryAfter, next);
      if (failed.isEmpty()) {
        LOG.warn(
            "Compensating job {} of type {} failed and it was no longer compensating in this"
                + " attempt's claim: {}",
            job.id(),
            job.type(),
            error,
            failure);
        return NOT_RUNNING;
      } else if (failed.get().ended()) {
        LOG.error(
            "Compensating job {} of type {} failed permanently after {} compensation attempt(s):"
                + " {}",
            job.id(),
            job.type(),
            job.compensationAttempts(),
            error,
            failure);
        return new Outcome(
            JobAttemptOutcome.COMPENSATION_FAILED,
            "COMPENSATION_FAILED"
                + nextOccurrenceOutcome(next, failed.get().nextOccurrenceScheduled()));
      } else {
        String when =
            retryAfter == null ? "as soon as possible" : "at " + failed.get().scheduledAt();
        LOG.warn(
            "Compensating job {} of type {} failed (compensation attempt {} of {}), compensating"
                + " again {}: {}",
            job.id(),
            job.type(),
            job.compensationAttempts(),
            failed.get().maxAttempts(),
            when,
            error,
            failure);
        return new Outcome(
            JobAttemptOutcome.COMPENSATION_RETRYING,
            "COMPENSATING again (attempt "
                + (job.compensationAttempts() + 1)
                + " of "
                + failed.get().maxAttempts()
                + ") "
                + when);
      }
    } catch (RuntimeException e) {
      e.addSuppressed(failure);
      LOG.warn("Recording the failed compensation of job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  /**
   * The next occurrence to schedule when this job (an occurrence of a recurring job) ends; {@code
   * null} for a one-off job, or if the recurring job is not registered on this node (a node that
   * registers it recreates the occurrence when it starts polling) or its recurrence fails.
   */
  private NewJob nextOccurrence(DefaultJob job) {
    if (job.recurrence() == null) {
      return null;
    }
    RecurringJob recurring = recurringJobs.apply(job.type());
    if (recurring == null) {
      LOG.debug(
          "Recurring job {} is not registered on bikesched node {} ({}), not scheduling its next"
              + " occurrence",
          job.type(),
          nodeName,
          nodeId);
      return null;
    }
    try {
      return recurring.occurrence(recurring.nextRun(job, Instant.now()));
    } catch (RuntimeException e) {
      LOG.warn("Computing the next occurrence of recurring job {} failed", job.type(), e);
      return null;
    }
  }

  private static String nextOccurrenceOutcome(NewJob next, boolean scheduled) {
    if (next == null) {
      return "";
    }
    return scheduled
        ? ", next occurrence at " + next.scheduledAt()
        : ", next occurrence already pending";
  }

  /**
   * The node's backoff delay after the job's current attempt failed; {@code null} to retry as soon
   * as possible. A misbehaving backoff must not prevent recording the failure.
   */
  private Duration backoffDelay(Job job) {
    return backoffDelay(job, job.attempts());
  }

  private Duration backoffDelay(Job job, int failedAttempt) {
    try {
      Duration delay = retryBackoff.delay(failedAttempt);
      if (delay == null || delay.isNegative()) {
        LOG.warn(
            "Retry backoff returned an invalid delay {} for job {}, retrying as soon as possible",
            delay,
            job.id());
        return null;
      }
      return delay.isZero() ? null : delay;
    } catch (RuntimeException e) {
      LOG.warn("Retry backoff failed for job {}, retrying as soon as possible", job.id(), e);
      return null;
    }
  }

  /**
   * Records a failed attempt; {@code maxAttempts} and {@code retryAfter} override the job's maximum
   * number of attempts and the delay of the next attempt if not {@code null}.
   */
  private Outcome recordFailure(
      DefaultJob job, String error, Throwable cause, Integer maxAttempts, Duration retryAfter) {
    if (retryAfter == null) {
      retryAfter = backoffDelay(job);
    }
    try {
      NewJob next = nextOccurrence(job);
      var failed =
          database.jobs().failJob(job.id(), job.lock(), error, maxAttempts, retryAfter, next);
      if (failed.isEmpty()) {
        LOG.warn(
            "Job {} of type {} failed and was no longer running in this attempt's claim: {}",
            job.id(),
            job.type(),
            error,
            cause);
        return NOT_RUNNING;
      } else if (failed.get().compensating()) {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s), compensating its steps: {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        return new Outcome(
            JobAttemptOutcome.COMPENSATING, "COMPENSATING after attempt " + job.attempts());
      } else if (!failed.get().retried()) {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s): {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        return new Outcome(
            JobAttemptOutcome.FAILED,
            "FAILED" + nextOccurrenceOutcome(next, failed.get().nextOccurrenceScheduled()));
      } else {
        String when =
            retryAfter == null ? "as soon as possible" : "at " + failed.get().scheduledAt();
        LOG.warn(
            "Job {} of type {} failed (attempt {} of {}), it will be retried {}: {}",
            job.id(),
            job.type(),
            job.attempts(),
            failed.get().maxAttempts(),
            when,
            error,
            cause);
        return new Outcome(
            JobAttemptOutcome.RETRYING,
            "RESCHEDULED for attempt "
                + (job.attempts() + 1)
                + " of "
                + failed.get().maxAttempts()
                + " "
                + when);
      }
    } catch (RuntimeException e) {
      if (cause != null) {
        e.addSuppressed(cause);
      }
      LOG.warn("Recording failure of job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }
}
