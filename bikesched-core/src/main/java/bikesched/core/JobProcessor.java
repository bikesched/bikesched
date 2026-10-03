package bikesched.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
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
 * <p>Thread-safe; holds no per-job state.
 */
final class JobProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(JobProcessor.class);

  // Outcomes of processing a job that could not be recorded as such, for the debug log.
  private static final String NOT_RUNNING =
      "not recorded, the job was no longer running on this node";
  private static final String NOT_RECORDED = "not recorded, see the warning above";

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Function<String, JobHandler> handlers;
  private final Function<String, RecurringJob> recurringJobs;
  private final ScheduledExecutorService timeoutExecutor;
  private final Duration defaultJobTimeout;
  private final Duration defaultEventTimeout;
  private final RetryBackoff retryBackoff;

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
   */
  JobProcessor(
      Database database,
      UUID nodeId,
      String nodeName,
      Function<String, JobHandler> handlers,
      Function<String, RecurringJob> recurringJobs,
      ScheduledExecutorService timeoutExecutor,
      Duration defaultJobTimeout,
      Duration defaultEventTimeout,
      RetryBackoff retryBackoff) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.recurringJobs = Objects.requireNonNull(recurringJobs, "recurringJobs");
    this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
    this.defaultJobTimeout = defaultJobTimeout;
    this.defaultEventTimeout = defaultEventTimeout;
    this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
  }

  /** Runs the job and logs the recorded outcome. */
  void process(DefaultJob job) {
    long start = System.nanoTime();
    String outcome = runAndRecord(job);
    LOG.debug(
        "Processed job {} of type {} (attempt {}) in {} ms: {}",
        job.id(),
        job.type(),
        job.attempts(),
        Duration.ofNanos(System.nanoTime() - start).toMillis(),
        outcome);
  }

  /**
   * Runs the job's handler (verified here because claiming is not filtered by job type) and records
   * the outcome.
   *
   * @return a description of the recorded outcome, for logging
   */
  private String runAndRecord(DefaultJob job) {
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
    Duration timeout = job.timeout().orElse(defaultJobTimeout);
    var attempt = new JobAttempt(job, Thread.currentThread(), timeoutExecutor, nodeId.toString());
    if (timeout != null) {
      attempt.startTimeout(timeout);
    }
    job.attach(attempt);
    job.defaultEventTimeout(defaultEventTimeout);
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
      handler.run(job);
    } catch (JobSuspended e) {
      // Control flow: the job waits for an event (job.suspension() is set), handled below.
    } catch (Exception e) {
      failure = e;
    }
    boolean timedOut = attempt.finish(); // no interrupt can arrive after this
    // Clear a pending interrupt (timeout, shutdown or the handler's own): this thread still has
    // to record the outcome, and blocking I/O of an interrupted virtual thread fails.
    Thread.interrupted();

    if (job.isCancelled()) {
      if (failure != null) {
        // The handler's decision to cancel wins over the failure: no retry.
        LOG.warn(
            "Job {} of type {} was cancelled, ignoring the exception thrown by its handler afterwards",
            job.id(),
            job.type(),
            failure);
      }
      return recordCancellation(job);
    } else if (job.failure() != null) {
      if (failure != null) {
        // The handler's explicit failure (and its retry settings) wins over the exception.
        LOG.warn(
            "Job {} of type {} was failed by its handler, ignoring the exception thrown afterwards",
            job.id(),
            job.type(),
            failure);
      }
      JobFailure explicit = job.failure();
      Integer retries = explicit.additionalAttempts();
      return recordFailure(
          job,
          explicit.message() == null ? "Job failed by its handler" : explicit.message(),
          null,
          retries == null ? null : job.attempts() + retries,
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
    } else if (timedOut) {
      return recordFailure(
          job, new BikeschedException("Job timed out after " + attempt.timeout(), failure));
    } else if (failure != null) {
      return recordFailure(job, failure);
    } else {
      try {
        NewJob next = nextOccurrence(job);
        var completed = database.completeJob(job.id(), nodeId, next);
        if (completed.isEmpty()) {
          LOG.warn("Job {} was no longer running on this node when completing it", job.id());
          return NOT_RUNNING;
        }
        return "SUCCEEDED" + nextOccurrenceOutcome(next, completed.get());
      } catch (RuntimeException e) {
        LOG.warn("Completing job {} failed", job.id(), e);
        return NOT_RECORDED;
      }
    }
  }

  private String recordSuspension(DefaultJob job, JobSuspended.Suspension suspension) {
    String event = "event " + suspension.eventName() + "/" + suspension.eventKey();
    try {
      var suspended =
          database.suspendJob(
              job.id(),
              nodeId,
              suspension.stepName(),
              suspension.eventName(),
              suspension.eventKey());
      if (suspended.isEmpty()) {
        LOG.warn(
            "Job {} of type {} waits for {} but was no longer running on this node",
            job.id(),
            job.type(),
            event);
        return NOT_RUNNING;
      }
      if (!suspended.get().waiting()) {
        return "RESCHEDULED, " + event + " was published while suspending the job";
      }
      Instant waitUntil = suspended.get().waitUntil();
      return "WAITING for "
          + event
          + " in step "
          + suspension.stepName()
          + (waitUntil == null ? "" : " until " + waitUntil);
    } catch (RuntimeException e) {
      LOG.warn("Suspending job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  private String recordCancellation(DefaultJob job) {
    String message = job.cancelMessage();
    try {
      NewJob next = nextOccurrence(job);
      var cancelled = database.cancelJob(job.id(), nodeId, message, next);
      if (cancelled.isPresent()) {
        LOG.info(
            "Job {} of type {} cancelled by its handler (attempt {} of {}){}",
            job.id(),
            job.type(),
            job.attempts(),
            job.maxAttempts(),
            message == null ? "" : ": " + message);
        return "CANCELLED" + nextOccurrenceOutcome(next, cancelled.get());
      } else {
        LOG.warn(
            "Job {} of type {} was cancelled but no longer running on this node",
            job.id(),
            job.type());
        return NOT_RUNNING;
      }
    } catch (RuntimeException e) {
      LOG.warn("Recording cancellation of job {} failed", job.id(), e);
      return NOT_RECORDED;
    }
  }

  private String recordFailure(DefaultJob job, Exception failure) {
    return recordFailure(job, failure.toString(), failure, null, null);
  }

  /**
   * The next occurrence to schedule when this job (an occurrence of a recurring job) ends; {@code
   * null} for a one-off job, or if the recurring job is not registered on this node (a node that
   * registers it recreates the occurrence on its next heartbeat) or its schedule fails.
   */
  private NewJob nextOccurrence(DefaultJob job) {
    if (job.schedule() == null) {
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
    try {
      Duration delay = retryBackoff.delay(job.attempts());
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
  private String recordFailure(
      DefaultJob job, String error, Throwable cause, Integer maxAttempts, Duration retryAfter) {
    if (retryAfter == null) {
      retryAfter = backoffDelay(job);
    }
    try {
      NewJob next = nextOccurrence(job);
      var failed = database.failJob(job.id(), nodeId, error, maxAttempts, retryAfter, next);
      if (failed.isEmpty()) {
        LOG.warn(
            "Job {} of type {} failed and was no longer running on this node: {}",
            job.id(),
            job.type(),
            error,
            cause);
        return NOT_RUNNING;
      } else if (!failed.get().retried()) {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s): {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        return "FAILED" + nextOccurrenceOutcome(next, failed.get().nextOccurrenceScheduled());
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
        return "RESCHEDULED for attempt "
            + (job.attempts() + 1)
            + " of "
            + failed.get().maxAttempts()
            + " "
            + when;
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
