// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bikesched.BikeschedException;
import org.bikesched.RetryBackoff;
import org.bikesched.internal.database.ClaimEnd;
import org.bikesched.internal.database.ClaimEnd.FollowUp;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.JobOrigin;
import org.bikesched.internal.database.JobPhase;
import org.bikesched.internal.database.JobStore;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.util.Retry;
import org.bikesched.job.Job;
import org.bikesched.job.JobFailureHandler;
import org.bikesched.job.JobHandler;
import org.bikesched.job.JobState;
import org.bikesched.job.listener.JobAttemptOutcome;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes the jobs claimed by a node, one call of {@link #process(DefaultJob, JobSlot)} per job
 * on the job's own thread: runs the {@link JobHandler} registered for the job's type within the
 * job's timeout and records the outcome in the database.
 *
 * <p>Outcomes, by precedence when a handler does several things:
 *
 * <ol>
 *   <li>{@link Job#cancel(String) cancelled}: {@code CANCELLED}, no retry
 *   <li>{@link Job#fail(String) failed explicitly}: retried with the handler's retry settings
 *   <li>{@link Job#waitForEvent(String, String, String, Class) waiting for an event}, a child job
 *       or the end of a sleep: {@code WAITING}
 *   <li>timed out, or threw: retried after the node's {@link RetryBackoff} while attempts remain,
 *       otherwise {@code FAILED}
 *   <li>returned normally: {@code SUCCEEDED}, with the result set by {@link Job#complete(Object)}
 * </ol>
 *
 * <p>A child job ending in a terminal state completes its parent's step waiting for it in the same
 * transaction (see {@code JobStore}).
 *
 * <p>A job whose cancellation was requested is cancelled without running its handler. A cancelled
 * or permanently failed job with completed compensable steps becomes {@code COMPENSATING} instead
 * of ending; when claimed again, its handler is replayed in compensation mode and the registered
 * compensations run, ending the job (or retrying the compensation, see {@link Job#newStep(String,
 * Class)}). A job that would end {@code FAILED} or {@code COMPENSATION_FAILED} and whose type has a
 * {@link JobFailureHandler} on this node becomes {@code FAILING} instead; when claimed again, only
 * the failure handler runs (again after a failure, up to {@code failureHandlerAttempts} runs), then
 * the job ends.
 *
 * <p>Each processed claim is reported to the {@link JobListener}: {@code onJobClaimed} before,
 * {@code onAttemptEnded} once the outcome is recorded.
 *
 * <p>Thread-safe; holds no per-job state.
 */
public final class JobProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(JobProcessor.class);

  /** Recording an outcome writes through the claim's lock token, so it is safe to retry. */
  private static final Retry RECORDING = Retry.of(3, Duration.ofMillis(200));

  // Outcomes of processing a job that could not be recorded as such.
  private static final Reported NOT_RUNNING =
      new Reported(
          JobAttemptOutcome.NOT_RECORDED,
          "not recorded, the job was no longer running in this attempt's claim");

  private static final Reported NOT_RECORDED =
      new Reported(JobAttemptOutcome.NOT_RECORDED, "not recorded, see the warning above");

  /** What processing a claim ended with; its description is for the debug log. */
  private sealed interface Outcome {

    String description();
  }

  /**
   * An outcome reported to the {@link JobListener}: recorded, or why it was not.
   *
   * @param kind what was recorded
   */
  private record Reported(JobAttemptOutcome kind, String description) implements Outcome {}

  /**
   * The thread of an attempt abandoned by the hard timeout returned after all: its outcome is
   * discarded, the abandoned attempt was reported when it was abandoned.
   */
  private record AbandonedReturned(String description) implements Outcome {}

  /** A claim being processed, started at {@code startNanos} ({@link System#nanoTime()}). */
  private record Started(JobClaim claim, long startNanos) {

    Duration elapsed() {
      return Duration.ofNanos(System.nanoTime() - startNanos);
    }
  }

  /** The code an attempt runs: the handler, the compensations or the failure handler. */
  @FunctionalInterface
  private interface AttemptBody {
    void run() throws Exception;
  }

  /** How an attempt run by {@link #runAttempt} ended, see {@link JobAttempt.End}. */
  private sealed interface AttemptEnd {

    /** The hard timeout abandoned the attempt; its thread returned now, with {@code failure}. */
    record Abandoned(@Nullable Exception failure, HardTimeout hardTimeout) implements AttemptEnd {}

    /** A step write found the job released while the attempt ran: record nothing. */
    record LockLost(@Nullable Exception failure) implements AttemptEnd {}

    /**
     * The attempt ended on its own thread: its outcome is to be recorded.
     *
     * @param end whether it returned in time or after its timeout
     * @param failure what the body threw, {@code null} if it returned or suspended the job
     * @param attempt the attempt, for the timeout of a timed out one
     */
    record Ended(JobAttempt.End end, @Nullable Exception failure, JobAttempt attempt)
        implements AttemptEnd {}
  }

  /**
   * What the handler's attempt amounts to, by precedence when it did several things (see the class
   * comment): decided by {@link #verdict}, then recorded.
   */
  private sealed interface Verdict {

    /** The handler cancelled the job. */
    record Cancelled(@Nullable String message) implements Verdict {}

    /** The handler failed the job explicitly, with its retry settings. */
    record FailedByHandler(DefaultJobFailure failure) implements Verdict {}

    /** The handler suspended the job at a waiting step, or postponed it. */
    record Suspended(JobSuspended.Suspension suspension) implements Verdict {}

    /** The attempt exceeded its timeout; {@code cause} is what the handler threw, if anything. */
    record TimedOut(@Nullable Duration timeout, @Nullable Exception cause) implements Verdict {}

    /** The handler threw. */
    record Threw(Exception failure) implements Verdict {}

    /** The handler returned normally. */
    record Returned() implements Verdict {}
  }

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Function<String, @Nullable JobHandler> handlers;
  private final Function<String, @Nullable JobFailureHandler> failureHandlers;
  private final int failureHandlerAttempts;
  private final Function<String, @Nullable RecurringJob> recurringJobs;
  private final ScheduledExecutorService timeoutExecutor;
  private final @Nullable Duration defaultJobTimeout;
  private final @Nullable Duration defaultEventTimeout;
  private final RetryBackoff retryBackoff;
  private final @Nullable HardTimeout hardTimeout;
  private final JobListener listener;

  /**
   * @param database the database
   * @param nodeId the node the jobs were claimed by
   * @param nodeName its name, for logging
   * @param handlers looks up the handler of a job type, {@code null} if none is registered
   * @param failureHandlers looks up the failure handler of a job type, {@code null} if none is
   *     registered
   * @param failureHandlerAttempts how often a failure handler runs before it is given up
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
      Function<String, @Nullable JobHandler> handlers,
      Function<String, @Nullable JobFailureHandler> failureHandlers,
      int failureHandlerAttempts,
      Function<String, @Nullable RecurringJob> recurringJobs,
      ScheduledExecutorService timeoutExecutor,
      @Nullable Duration defaultJobTimeout,
      @Nullable Duration defaultEventTimeout,
      RetryBackoff retryBackoff,
      @Nullable HardTimeout hardTimeout,
      JobListener listener) {
    this.database = database;
    this.nodeId = nodeId;
    this.nodeName = nodeName;
    this.handlers = handlers;
    this.failureHandlers = failureHandlers;
    this.failureHandlerAttempts = failureHandlerAttempts;
    this.recurringJobs = recurringJobs;
    this.timeoutExecutor = timeoutExecutor;
    this.defaultJobTimeout = defaultJobTimeout;
    this.defaultEventTimeout = defaultEventTimeout;
    this.retryBackoff = retryBackoff;
    this.hardTimeout = hardTimeout;
    this.listener = listener;
  }

  /**
   * Starts an attempt of the job on the current thread: its timeout (the job's own or the node
   * default, if any) and, with a hard timeout, abandoning it if it ignores the timeout.
   */
  private JobAttempt startAttempt(DefaultJob job, JobSlot slot, Started started) {
    HardTimeout hard = hardTimeout;
    var attempt =
        new JobAttempt(
            job,
            Thread.currentThread(),
            timeoutExecutor,
            nodeId.toString(),
            hard == null ? null : hard.grace(),
            hard == null
                ? ignored -> {}
                : abandoned ->
                    hard.abandon(
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
   * Runs the body in one attempt of the job. The attempt's timer and, with a hard timeout, the
   * abandoning of attempts that ignore it are set up by {@link #startAttempt}; the pending
   * interrupt is cleared afterwards, because this thread still has to record the outcome and
   * blocking I/O of an interrupted virtual thread fails.
   */
  private AttemptEnd runAttempt(DefaultJob job, JobSlot slot, Started started, AttemptBody body) {
    JobAttempt attempt = startAttempt(job, slot, started);
    Exception failure = null;
    try {
      body.run();
    } catch (JobSuspended e) {
      // Control flow: the job waits (job.suspension() is set), recorded by the caller.
    } catch (Exception e) {
      failure = e;
    }
    JobAttempt.End end = attempt.finish(); // no interrupt can arrive after this
    // Clear a pending interrupt (timeout, shutdown or the handler's own): this thread still has
    // to record the outcome, and blocking I/O of an interrupted virtual thread fails.
    Thread.interrupted();
    HardTimeout hard = hardTimeout;
    if (end == JobAttempt.End.ABANDONED && hard != null) {
      return new AttemptEnd.Abandoned(failure, hard);
    }
    if (job.isLockLost()) {
      return new AttemptEnd.LockLost(failure);
    }
    return new AttemptEnd.Ended(end, failure, attempt);
  }

  /**
   * Records an attempt abandoned by the hard timeout as failed, like a timed-out attempt (on the
   * hard timeout's recorder thread, while the handler's thread may still run).
   */
  private void recordAbandoned(DefaultJob job, Exception failure, Started started) {
    Reported outcome =
        switch (job.phase()) {
          case JobPhase.HandleFailure failing -> recordFailureHandlerFailure(job, failing, failure);
          case JobPhase.Compensate compensating ->
              recordCompensationFailure(job, compensating, null, failure);
          case JobPhase.Run ignored -> recordFailure(job, failure);
        };
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
  private Reported abandon(DefaultJob job, @Nullable Exception failure) {
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
    return new Reported(JobAttemptOutcome.NOT_RECORDED, "abandoned, the job was released");
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
      switch (outcome) {
        case AbandonedReturned ignored ->
            notifyListener(job, () -> listener.onAbandonedAttemptReturned(started.claim()));
        case Reported(JobAttemptOutcome kind, String ignored) -> {
          var result = new JobAttemptResult(started.claim(), kind, elapsed, false);
          notifyListener(job, () -> listener.onAttemptEnded(result));
        }
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
    JobPhase phase = job.phase();
    return new JobClaim(
        job.id(),
        job.type(),
        phase.attempt(),
        phase instanceof JobPhase.Compensate,
        phase instanceof JobPhase.HandleFailure,
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
   * Decides what the claimed job needs (its failure handler, the compensation of its steps, the
   * cancellation it asked for, or its handler) and records the outcome.
   */
  private Outcome runAndRecord(DefaultJob job, JobSlot slot, Started started) {
    return switch (job.phase()) {
      case JobPhase.HandleFailure failing -> runFailureHandler(job, failing, slot, started);
      case JobPhase.Compensate compensating -> compensate(job, compensating, slot, started);
      case JobPhase.Run ignored when job.cancelRequested() ->
          recordCancellation(job, job.cancelRequestMessage(), false);
      case JobPhase.Run ignored -> runHandler(job, slot, started);
    };
  }

  /**
   * The handler registered for the job's type, verified here because claiming is not filtered by
   * job type.
   *
   * @param doing what cannot be done without it, for the log
   */
  private Optional<JobHandler> handler(DefaultJob job, String doing) {
    JobHandler handler = handlers.apply(job.type());
    if (handler == null) {
      LOG.error(
          "No handler registered for job type {} on bikesched node {} ({}), cannot {} job {}",
          job.type(),
          nodeName,
          nodeId,
          doing,
          job.id());
    }
    return Optional.ofNullable(handler);
  }

  private BikeschedException noHandler(DefaultJob job) {
    return new BikeschedException(
        "No handler registered for job type '" + job.type() + "' on bikesched node " + nodeName);
  }

  /** Runs the handler in one attempt and records what it did, see {@link #recordOutcome}. */
  private Outcome runHandler(DefaultJob job, JobSlot slot, Started started) {
    Optional<JobHandler> handler = handler(job, "run");
    if (handler.isEmpty()) {
      return recordFailure(job, noHandler(job));
    }
    AttemptBody body =
        () -> {
          LOG.debug(
              "Executing job {} of type {} (attempt {} of {}) on bikesched node {} ({})",
              job.id(),
              job.type(),
              job.attempts(),
              job.maxAttempts(),
              nodeName,
              nodeId);
          handler.get().handle(job);
        };
    return switch (runAttempt(job, slot, started, body)) {
      case AttemptEnd.Abandoned(@Nullable Exception failure, HardTimeout hard) ->
          new AbandonedReturned(hard.returned(job, failure));
      case AttemptEnd.LockLost(@Nullable Exception failure) -> abandon(job, failure);
      case AttemptEnd.Ended(JobAttempt.End end, @Nullable Exception failure, JobAttempt attempt) ->
          recordOutcome(job, verdict(job, failure, end, attempt), failure);
    };
  }

  /** What the handler's attempt amounts to, by the precedence documented on the class. */
  private static Verdict verdict(
      DefaultJob job, @Nullable Exception thrown, JobAttempt.End end, JobAttempt attempt) {
    if (job.isCancelled()) {
      return new Verdict.Cancelled(job.cancelMessage());
    }
    if (job.failure() != null) {
      return new Verdict.FailedByHandler(job.failure());
    }
    if (job.suspension() != null) {
      return new Verdict.Suspended(job.suspension());
    }
    if (end == JobAttempt.End.TIMED_OUT) {
      return new Verdict.TimedOut(attempt.timeout(), thrown);
    }
    return thrown == null ? new Verdict.Returned() : new Verdict.Threw(thrown);
  }

  /**
   * Records what the handler's attempt amounts to.
   *
   * @param thrown the exception the handler threw, {@code null} if it returned or suspended
   */
  private Reported recordOutcome(DefaultJob job, Verdict verdict, @Nullable Exception thrown) {
    if (thrown != null) {
      warnIfIgnored(job, verdict, thrown);
    }
    return switch (verdict) {
      case Verdict.Cancelled(@Nullable String message) -> recordCancellation(job, message, true);
      case Verdict.FailedByHandler(DefaultJobFailure failure) ->
          recordExplicitFailure(job, failure);
      case Verdict.Suspended(JobSuspended.Wait wait) -> recordSuspension(job, wait);
      case Verdict.Suspended(JobSuspended.PostponeSuspension postponed) ->
          recordPostponement(job, postponed);
      case Verdict.TimedOut(@Nullable Duration timeout, @Nullable Exception cause) ->
          recordFailure(job, new BikeschedException("Job timed out after " + timeout, cause));
      case Verdict.Threw(Exception failure) when job.isFailureHandlingPending() ->
          // A step's failure handler has to run again: one more attempt, whatever the maximum.
          recordFailure(job, failure.toString(), failure, job.attempts() + 1, null);
      case Verdict.Threw(Exception failure) -> recordFailure(job, failure);
      case Verdict.Returned() -> recordCompletion(job);
    };
  }

  /** The handler threw after deciding the outcome otherwise: its decision wins. */
  private static void warnIfIgnored(DefaultJob job, Verdict verdict, Exception thrown) {
    switch (verdict) {
      case Verdict.Cancelled ignored ->
          // The handler's decision to cancel wins over the failure: no retry.
          LOG.warn(
              "Job {} of type {} was cancelled, ignoring the exception thrown by its handler afterwards",
              job.id(),
              job.type(),
              thrown);
      case Verdict.FailedByHandler ignored ->
          // The handler's explicit failure (and its retry settings) wins over the exception.
          LOG.warn(
              "Job {} of type {} was failed by its handler, ignoring the exception thrown afterwards",
              job.id(),
              job.type(),
              thrown);
      case Verdict.Suspended(JobSuspended.Suspension suspension) ->
          // The handler caught the suspension and threw afterwards: the wait (or postponement)
          // wins.
          LOG.warn(
              "Job {} of type {} was suspended {}, ignoring the exception thrown by its handler"
                  + " afterwards",
              job.id(),
              job.type(),
              suspension,
              thrown);
      case Verdict.TimedOut ignored -> {}
      case Verdict.Threw ignored -> {}
      case Verdict.Returned ignored -> {}
    }
  }

  private Reported recordExplicitFailure(DefaultJob job, DefaultJobFailure failure) {
    Integer remainingAttempts = failure.remainingAttempts();
    return recordFailure(
        job,
        failure.message() == null ? "Job failed by its handler" : failure.message(),
        null,
        remainingAttempts == null ? null : job.attempts() + remainingAttempts,
        failure.retryDelay());
  }

  private Reported recordCompletion(DefaultJob job) {
    ClaimEnd end = end(job);
    return recording(
        job,
        "Completing",
        null,
        () -> database.jobs().completeJob(end, job.resultJson()),
        completed ->
            new Reported(
                JobAttemptOutcome.SUCCEEDED,
                "SUCCEEDED" + nextOccurrenceOutcome(end, completed.nextOccurrenceScheduled())),
        () ->
            LOG.warn(
                "Job {} was no longer running in this attempt's claim when completing it",
                job.id()));
  }

  /**
   * Writes an outcome (retried, see {@link #RECORDING}) and describes it.
   *
   * @param action what the write does, for the messages, e.g. {@code "Completing"}
   * @param cause the failure being recorded, if any: attached to the exception of a failed write
   * @param write the write; empty if the job is no longer running in this attempt's claim
   * @param recorded describes what was written
   * @param notRunning logs that the job was no longer running in this attempt's claim
   */
  private <R extends @Nullable Object> Reported recording(
      DefaultJob job,
      String action,
      @Nullable Throwable cause,
      Supplier<Optional<R>> write,
      Function<R, Reported> recorded,
      Runnable notRunning) {
    try {
      Optional<R> written = RECORDING.call(action + " job " + job.id() + " failed", write);
      if (written.isEmpty()) {
        notRunning.run();
        return NOT_RUNNING;
      }
      return recorded.apply(written.get());
    } catch (RuntimeException e) {
      if (cause != null) {
        e.addSuppressed(cause);
      }
      LOG.warn("{} job {} failed", action, job.id(), e);
      return NOT_RECORDED;
    }
  }

  private Reported recordPostponement(DefaultJob job, JobSuspended.PostponeSuspension postponed) {
    return recording(
        job,
        "Postponing",
        null,
        () ->
            database
                .steps()
                .postponeJob(job.id(), job.lock(), postponed.stepName(), postponed.delay()),
        stored -> {
          String step = postponed.stepName() == null ? "" : " in step " + postponed.stepName();
          return new Reported(
              JobAttemptOutcome.POSTPONED,
              stored.cancelRequested()
                  ? "POSTPONED" + step + ", due again right away (cancellation requested)"
                  : "POSTPONED" + step + " until " + stored.scheduledAt());
        },
        () ->
            LOG.warn(
                "Job {} of type {} was postponed but was no longer running in this attempt's claim",
                job.id(),
                job.type()));
  }

  private Reported recordSuspension(DefaultJob job, JobSuspended.Wait suspension) {
    return recording(
        job,
        "Suspending",
        null,
        () ->
            switch (suspension) {
              case JobSuspended.EventSuspension wait ->
                  database
                      .events()
                      .suspendJob(
                          job.id(), job.lock(), wait.stepName(), wait.eventName(), wait.eventKey());
              case JobSuspended.SleepSuspension sleep ->
                  database.steps().suspendJob(job.id(), job.lock(), sleep.stepName());
              case JobSuspended.JobSuspension wait ->
                  database
                      .steps()
                      .suspendAtChildJob(job.id(), job.lock(), wait.stepName(), wait.childJobId());
              case JobSuspended.PollSuspension poll ->
                  database
                      .steps()
                      .suspendAtPoll(job.id(), job.lock(), poll.stepName(), !poll.resumed());
            },
        suspended ->
            new Reported(
                JobAttemptOutcome.WAITING,
                suspended.waiting()
                    ? waitingOutcome(suspension, suspended.waitUntil())
                    : "RESCHEDULED, " + rescheduledOutcome(suspension)),
        () ->
            LOG.warn(
                "Job {} of type {} waits in step {} but was no longer running in this attempt's"
                    + " claim",
                job.id(),
                job.type(),
                suspension.stepName()));
  }

  /** Why a suspended job was scheduled right away instead of waiting. */
  private static String rescheduledOutcome(JobSuspended.Wait suspension) {
    return switch (suspension) {
      case JobSuspended.EventSuspension wait ->
          wait.event() + " was published while suspending the job";
      case JobSuspended.SleepSuspension ignored ->
          "the wake-up time passed while suspending the job";
      case JobSuspended.JobSuspension wait ->
          "job " + wait.childJobId() + " ended while suspending the job";
      case JobSuspended.PollSuspension ignored ->
          "the next check became due while suspending the job";
    };
  }

  /** What a suspended job waits for. */
  private static String waitingOutcome(JobSuspended.Wait suspension, @Nullable Instant waitUntil) {
    String until = waitUntil == null ? "" : " until " + waitUntil;
    return switch (suspension) {
      case JobSuspended.EventSuspension wait ->
          "WAITING for " + wait.event() + " in step " + wait.stepName() + until;
      case JobSuspended.SleepSuspension sleep ->
          "SLEEPING in step " + sleep.stepName() + " until " + waitUntil;
      case JobSuspended.JobSuspension wait ->
          "WAITING for job " + wait.childJobId() + " in step " + wait.stepName() + until;
      case JobSuspended.PollSuspension poll ->
          "POLLING in step "
              + poll.stepName()
              + " after check "
              + poll.check()
              + ", next check at "
              + waitUntil;
    };
  }

  /**
   * Records the cancellation of a job: by its handler ({@code byHandler}), or on request right
   * after claiming it, without running the handler (which does not use up an attempt).
   */
  private Reported recordCancellation(DefaultJob job, @Nullable String message, boolean byHandler) {
    ClaimEnd end = end(job);
    return recording(
        job,
        "Cancelling",
        null,
        () -> database.jobs().cancelJob(end, message, byHandler),
        cancelled -> {
          boolean compensating = cancelled.state() == JobState.COMPENSATING;
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
              ? new Reported(JobAttemptOutcome.COMPENSATING, "COMPENSATING after cancellation")
              : new Reported(
                  JobAttemptOutcome.CANCELLED,
                  "CANCELLED" + nextOccurrenceOutcome(end, cancelled.nextOccurrenceScheduled()));
        },
        () ->
            LOG.warn(
                "Job {} of type {} was cancelled but no longer running in this attempt's claim",
                job.id(),
                job.type()));
  }

  private Reported recordFailure(DefaultJob job, Exception failure) {
    return recordFailure(job, failure.toString(), failure, null, null);
  }

  /**
   * Compensates a job: replays its handler in compensation mode, which registers the compensations
   * of its completed steps until the first step that has not completed, then runs them, latest step
   * first, and records the outcome.
   */
  private Outcome compensate(
      DefaultJob job, JobPhase.Compensate compensating, JobSlot slot, Started started) {
    Optional<JobHandler> handler = handler(job, "compensate");
    if (handler.isEmpty()) {
      return recordCompensationFailure(job, compensating, null, noHandler(job));
    }
    var progress = new CompensationProgress();
    AttemptBody body =
        () -> {
          LOG.debug(
              "Compensating job {} of type {} (compensation attempt {} of {}) on bikesched node {}"
                  + " ({})",
              job.id(),
              job.type(),
              compensating.attempt(),
              job.maxAttempts(),
              nodeName,
              nodeId);
          try {
            handler.get().handle(job);
          } catch (CompensationReplayEnded e) {
            // The replay reached a step that has not completed: nothing to compensate after it.
          } catch (Exception e) {
            LOG.debug(
                "Handler of job {} threw while replaying it for compensation, compensating the"
                    + " steps completed before: {}",
                job.id(),
                e.toString());
          }
          for (CompensationReplay.PendingCompensation compensation : job.compensations()) {
            progress.step = compensation.stepName();
            job.compensate(compensation);
            progress.compensated++;
          }
          progress.step = null;
        };
    return switch (runAttempt(job, slot, started, body)) {
      case AttemptEnd.Abandoned(@Nullable Exception failure, HardTimeout hard) ->
          new AbandonedReturned(hard.returned(job, failure));
      case AttemptEnd.LockLost(@Nullable Exception failure) -> abandon(job, failure);
      case AttemptEnd.Ended(JobAttempt.End end, @Nullable Exception failure, JobAttempt attempt)
          when failure != null ->
          recordCompensationFailure(
              job,
              compensating,
              progress.step,
              end == JobAttempt.End.TIMED_OUT
                  ? new BikeschedException(
                      "Compensation timed out after " + attempt.timeout(), failure)
                  : failure);
      case AttemptEnd.Ended ignored -> recordCompensated(job, progress.compensated);
    };
  }

  /** How far the compensations of an attempt got; written and read on the job's thread only. */
  private static final class CompensationProgress {

    /** The compensation running (or failed), {@code null} if none. */
    private @Nullable String step;

    /** The number of compensations that ran. */
    private int compensated;
  }

  private Reported recordCompensated(DefaultJob job, int compensated) {
    ClaimEnd end = end(job);
    return recording(
        job,
        "Completing the compensation of",
        null,
        () -> database.jobs().completeCompensation(end),
        ended -> {
          LOG.info(
              "Job {} of type {} compensated {} step(s), it is {}",
              job.id(),
              job.type(),
              compensated,
              ended.state());
          return switch (ended.state()) {
            case FAILING ->
                new Reported(
                    JobAttemptOutcome.FAILING,
                    "COMPENSATED "
                        + compensated
                        + " step(s), FAILING: its failure handler runs next");
            case CANCELLED, FAILED ->
                new Reported(
                    JobAttemptOutcome.COMPENSATED,
                    "COMPENSATED "
                        + compensated
                        + " step(s), "
                        + ended.state()
                        + nextOccurrenceOutcome(end, ended.nextOccurrenceScheduled()));
            case SCHEDULED, RUNNING, WAITING, SUCCEEDED, COMPENSATING, COMPENSATION_FAILED ->
                throw unexpected(job, ended.state());
          };
        },
        () ->
            LOG.warn(
                "Job {} was no longer compensating in this attempt's claim when completing it",
                job.id()));
  }

  /** Records a failed compensation attempt; {@code step} is the failed compensation, if any. */
  private Reported recordCompensationFailure(
      DefaultJob job, JobPhase.Compensate compensating, @Nullable String step, Exception failure) {
    String error =
        step == null ? failure.toString() : "Compensating step '" + step + "' failed: " + failure;
    int attempt = compensating.attempt();
    Duration retryAfter = backoffDelay(job, attempt);
    ClaimEnd end = end(job);
    return recording(
        job,
        "Recording the failed compensation of",
        failure,
        () -> database.jobs().failCompensation(end, error, retryAfter),
        failed ->
            switch (failed.state()) {
              case FAILING -> {
                LOG.error(
                    "Compensating job {} of type {} failed permanently after {} compensation"
                        + " attempt(s), running its failure handler: {}",
                    job.id(),
                    job.type(),
                    attempt,
                    error,
                    failure);
                yield new Reported(
                    JobAttemptOutcome.FAILING, "FAILING after compensation attempt " + attempt);
              }
              case COMPENSATION_FAILED, FAILED -> {
                LOG.error(
                    "Compensating job {} of type {} failed permanently after {} compensation"
                        + " attempt(s): {}",
                    job.id(),
                    job.type(),
                    attempt,
                    error,
                    failure);
                yield new Reported(
                    JobAttemptOutcome.COMPENSATION_FAILED,
                    "COMPENSATION_FAILED"
                        + nextOccurrenceOutcome(end, failed.nextOccurrenceScheduled()));
              }
              case COMPENSATING -> {
                String when =
                    retryAfter == null ? "as soon as possible" : "at " + failed.scheduledAt();
                LOG.warn(
                    "Compensating job {} of type {} failed (compensation attempt {} of {}),"
                        + " compensating again {}: {}",
                    job.id(),
                    job.type(),
                    attempt,
                    failed.maxAttempts(),
                    when,
                    error,
                    failure);
                yield new Reported(
                    JobAttemptOutcome.COMPENSATION_RETRYING,
                    "COMPENSATING again (attempt "
                        + (attempt + 1)
                        + " of "
                        + failed.maxAttempts()
                        + ") "
                        + when);
              }
              case SCHEDULED, RUNNING, WAITING, SUCCEEDED, CANCELLED ->
                  throw unexpected(job, failed.state());
            },
        () ->
            LOG.warn(
                "Compensating job {} of type {} failed and it was no longer compensating in this"
                    + " attempt's claim: {}",
                job.id(),
                job.type(),
                error,
                failure));
  }

  /** Whether the job's type has a failure handler on this node: then it fails into FAILING. */
  private boolean hasFailureHandler(DefaultJob job) {
    return failureHandlers.apply(job.type()) != null;
  }

  /**
   * Runs the failure handler of a {@code FAILING} job in one attempt (within the job's timeout,
   * like {@link #runHandler}) and records the outcome: the job ends once the handler returned,
   * otherwise the handler runs again, until it ran {@code failureHandlerAttempts} times.
   */
  private Outcome runFailureHandler(
      DefaultJob job, JobPhase.HandleFailure failing, JobSlot slot, Started started) {
    JobFailureHandler handler = failureHandlers.apply(job.type());
    if (handler == null) {
      LOG.warn(
          "No failure handler registered for job type {} on bikesched node {} ({}), job {} ends {}"
              + " without it",
          job.type(),
          nodeName,
          nodeId,
          job.id(),
          failing.endState());
      return recordFailingEnded(
          job, "ended without failure handler: none registered on bikesched node " + nodeName);
    }
    AttemptBody body =
        () -> {
          LOG.debug(
              "Running the failure handler of job {} of type {} (run {} of {}) on bikesched node {}"
                  + " ({})",
              job.id(),
              job.type(),
              failing.attempt(),
              failureHandlerAttempts,
              nodeName,
              nodeId);
          handler.handle(new DefaultFailedJob(job, failing));
        };
    return switch (runAttempt(job, slot, started, body)) {
      case AttemptEnd.Abandoned(@Nullable Exception failure, HardTimeout hard) ->
          new AbandonedReturned(hard.returned(job, failure));
      case AttemptEnd.LockLost(@Nullable Exception failure) -> abandon(job, failure);
      case AttemptEnd.Ended(JobAttempt.End end, @Nullable Exception failure, JobAttempt attempt)
          when end == JobAttempt.End.TIMED_OUT ->
          recordFailureHandlerFailure(
              job,
              failing,
              new BikeschedException(
                  "Failure handler timed out after " + attempt.timeout(), failure));
      case AttemptEnd.Ended(JobAttempt.End ignored, Exception failure, JobAttempt alsoIgnored)
          when failure != null ->
          recordFailureHandlerFailure(job, failing, failure);
      case AttemptEnd.Ended ignored -> recordFailingEnded(job, "failure handler ran");
    };
  }

  /**
   * Records a failed run of a job's failure handler: it runs again after the node's backoff, or,
   * once it ran {@code failureHandlerAttempts} times, the job ends without it.
   */
  private Reported recordFailureHandlerFailure(
      DefaultJob job, JobPhase.HandleFailure failing, Exception failure) {
    int run = failing.attempt();
    if (run >= failureHandlerAttempts) {
      LOG.error(
          "Failure handler of job {} of type {} gave up after {} run(s), the job ends {}",
          job.id(),
          job.type(),
          run,
          failing.endState(),
          failure);
      return recordFailingEnded(
          job, "failure handler gave up after " + run + " run(s): " + failure);
    }
    Duration retryAfter = backoffDelay(job, run);
    return recording(
        job,
        "Recording the failed failure handler of",
        failure,
        () ->
            database
                .jobs()
                .retryFailureHandler(job.id(), job.lock(), failure.toString(), retryAfter),
        again -> {
          LOG.warn(
              "Failure handler of job {} of type {} failed (run {} of {}), it runs again at {}",
              job.id(),
              job.type(),
              run,
              failureHandlerAttempts,
              again,
              failure);
          return new Reported(
              JobAttemptOutcome.FAILURE_HANDLER_RETRYING,
              "FAILING, failure handler runs again (run "
                  + (run + 1)
                  + " of "
                  + failureHandlerAttempts
                  + ") at "
                  + again);
        },
        () ->
            LOG.warn(
                "Failure handler of job {} of type {} failed and the job was no longer failing in"
                    + " this attempt's claim",
                job.id(),
                job.type(),
                failure));
  }

  /** Ends a {@code FAILING} job in the state it was failing for. */
  private Reported recordFailingEnded(DefaultJob job, String detail) {
    ClaimEnd end = end(job);
    return recording(
        job,
        "Ending failing",
        null,
        () -> database.jobs().endFailing(end, detail),
        ended -> {
          LOG.info("Job {} of type {} is {}: {}", job.id(), job.type(), ended.state(), detail);
          JobAttemptOutcome kind =
              switch (ended.state()) {
                case COMPENSATION_FAILED -> JobAttemptOutcome.COMPENSATION_FAILED;
                case FAILED -> JobAttemptOutcome.FAILED;
                case SCHEDULED, RUNNING, WAITING, SUCCEEDED, CANCELLED, COMPENSATING, FAILING ->
                    throw unexpected(job, ended.state());
              };
          return new Reported(
              kind,
              ended.state()
                  + " after its failure handler"
                  + nextOccurrenceOutcome(end, ended.nextOccurrenceScheduled()));
        },
        () ->
            LOG.warn(
                "Job {} of type {} was no longer failing in this attempt's claim when ending it",
                job.id(),
                job.type()));
  }

  /** The ending claim of the job: who holds it and what follows from its end. */
  private ClaimEnd end(DefaultJob job) {
    return new ClaimEnd(job.id(), job.lock(), hasFailureHandler(job), followUp(job));
  }

  /** What follows once the job ended: by its origin. */
  private FollowUp followUp(DefaultJob job) {
    return switch (job.origin()) {
      case JobOrigin.OneOff ignored -> new FollowUp.None();
      case JobOrigin.Child(UUID parentJobId) -> new FollowUp.NotifyParent(parentJobId);
      case JobOrigin.Occurrence ignored ->
          nextOccurrence(job)
              .<FollowUp>map(FollowUp.ScheduleNext::new)
              .orElseGet(FollowUp.None::new);
    };
  }

  /**
   * The next occurrence to schedule when this occurrence of a recurring job ends; empty if the
   * recurring job is not registered on this node (a node that registers it recreates the occurrence
   * when it starts polling) or its recurrence fails.
   */
  private Optional<NewJob> nextOccurrence(DefaultJob job) {
    RecurringJob recurring = recurringJobs.apply(job.type());
    if (recurring == null) {
      LOG.debug(
          "Recurring job {} is not registered on bikesched node {} ({}), not scheduling its next"
              + " occurrence",
          job.type(),
          nodeName,
          nodeId);
      return Optional.empty();
    }
    try {
      return Optional.of(recurring.occurrence(recurring.nextRun(job, Instant.now())));
    } catch (RuntimeException e) {
      LOG.warn("Computing the next occurrence of recurring job {} failed", job.type(), e);
      return Optional.empty();
    }
  }

  private static String nextOccurrenceOutcome(ClaimEnd end, boolean scheduled) {
    return switch (end.followUp()) {
      case FollowUp.ScheduleNext(NewJob next) ->
          scheduled
              ? ", next occurrence at " + next.scheduledAt()
              : ", next occurrence already pending";
      case FollowUp.None ignored -> "";
      case FollowUp.NotifyParent ignored -> "";
    };
  }

  /** A state the store does not return for the recorded change. */
  private static IllegalStateException unexpected(DefaultJob job, JobState state) {
    return new IllegalStateException("Unexpected state " + state + " of job " + job.id());
  }

  /**
   * The node's backoff delay after the job's current attempt failed; {@code null} to retry as soon
   * as possible. A misbehaving backoff must not prevent recording the failure.
   */
  private @Nullable Duration backoffDelay(DefaultJob job) {
    var stepRetry = job.stepRetry();
    if (stepRetry != null) {
      return backoffDelay(job, stepRetry.backoff(), stepRetry.failedAttempts());
    }
    return backoffDelay(job, job.attempts());
  }

  private @Nullable Duration backoffDelay(Job job, int failedAttempt) {
    return backoffDelay(job, retryBackoff, failedAttempt);
  }

  private @Nullable Duration backoffDelay(Job job, RetryBackoff backoff, int failedAttempt) {
    try {
      Duration delay = backoff.delay(failedAttempt);
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
  private Reported recordFailure(
      DefaultJob job,
      String error,
      @Nullable Throwable cause,
      @Nullable Integer maxAttempts,
      @Nullable Duration retryAfter) {
    Duration retryIn = retryAfter == null ? backoffDelay(job) : retryAfter;
    ClaimEnd end = end(job);
    return recording(
        job,
        "Failing",
        cause,
        () -> database.jobs().failJob(end, error, maxAttempts, retryIn),
        failed -> recordedFailure(job, error, cause, retryIn, end, failed),
        () ->
            LOG.warn(
                "Job {} of type {} failed and was no longer running in this attempt's claim: {}",
                job.id(),
                job.type(),
                error,
                cause));
  }

  private Reported recordedFailure(
      DefaultJob job,
      String error,
      @Nullable Throwable cause,
      @Nullable Duration retryIn,
      ClaimEnd end,
      JobStore.FailedJob failed) {
    return switch (failed.state()) {
      case COMPENSATING -> {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s), compensating its steps: {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        yield new Reported(
            JobAttemptOutcome.COMPENSATING, "COMPENSATING after attempt " + job.attempts());
      }
      case FAILING -> {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s), running its failure handler:"
                + " {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        yield new Reported(JobAttemptOutcome.FAILING, "FAILING after attempt " + job.attempts());
      }
      case FAILED -> {
        LOG.error(
            "Job {} of type {} failed permanently after {} attempt(s): {}",
            job.id(),
            job.type(),
            job.attempts(),
            error,
            cause);
        yield new Reported(
            JobAttemptOutcome.FAILED,
            "FAILED" + nextOccurrenceOutcome(end, failed.nextOccurrenceScheduled()));
      }
      case SCHEDULED -> {
        String when = retryIn == null ? "as soon as possible" : "at " + failed.scheduledAt();
        LOG.warn(
            "Job {} of type {} failed (attempt {} of {}), it will be retried {}: {}",
            job.id(),
            job.type(),
            job.attempts(),
            failed.maxAttempts(),
            when,
            error,
            cause);
        yield new Reported(
            JobAttemptOutcome.RETRYING,
            "RESCHEDULED for attempt "
                + (job.attempts() + 1)
                + " of "
                + failed.maxAttempts()
                + " "
                + when);
      }
      case RUNNING, WAITING, SUCCEEDED, CANCELLED, COMPENSATION_FAILED ->
          throw unexpected(job, failed.state());
    };
  }
}
