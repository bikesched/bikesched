// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_steps} table: durable steps of jobs, sleeps, waits for child jobs, polls,
 * and suspending a job at a waiting step (event steps are handled by {@link EventStore}, starting
 * child jobs by {@link JobStore}). Changes are recorded in the {@link HistoryStore job history}, if
 * enabled. Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 *
 * <p>Every write is fenced by the job's lock ({@link JobLock}): it only takes effect while the job
 * is {@code RUNNING} with the lock token of the writing attempt's claim, checked in the same
 * statement. A write that finds the job released (or claimed again since) throws a {@link
 * JobLockLostException}. A running step is also locked by its node ({@code locked_by}), so only the
 * node that started an attempt of a step can complete or fail it.
 */
public final class StepStore {

  private final DatabaseAccess access;
  private final HistoryStore history;

  StepStore(DatabaseAccess access, HistoryStore history) {
    this.access = access;
    this.history = history;
  }

  // The fence of every step write: the job is running with the writing attempt's lock token (job
  // id, token). The
  // share lock makes a concurrent release (an update of the job row) wait for the write's
  // transaction, so the check stays true until the write is committed.
  private static final String JOB_LOCKED =
      """
            exists (select 1 from bikesched_jobs
                     where id = ? and lock_token = ? and state = 'RUNNING' for share)""";

  private static final String FIND_STEP_SQL =
      """
            select kind, state, result::text, attempts, compensable from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a regular step that has not succeeded yet; returns its attempt number.
  // Returns no row if the step already succeeded (e.g. concurrently), was compensated since, is
  // another kind of step, or the job is no longer running on this node.
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, locked_by, started_at)
            select ?, ?, 'RUNNING', 1, ?, now()
             where %s
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1, failure_attempts = 0,
                   locked_by = excluded.locked_by, started_at = now(), finished_at = null
             where bikesched_steps.kind = 'STEP' and bikesched_steps.state not in ('SUCCEEDED', 'COMPENSATED')
            returning attempts"""
          .formatted(JOB_LOCKED);

  private static final String COMPLETE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, compensable = ?, locked_by = null, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  // A step with a compensation: a completed step, or a child job step once the child was started
  // (see JobStore.COMPENSATE_SQL).
  private static final String COMPENSABLE_STEP =
      """
            (state = 'SUCCEEDED' or kind = 'CHILD_JOB' and state in ('WAITING', 'CANCELLED', 'TIMED_OUT'))""";

  // A child job step keeps its child's outcome (child_state, last_error) when it is compensated.
  private static final String COMPENSATE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'COMPENSATED', last_error = case when kind = 'CHILD_JOB' and child_state is not null then last_error end
             where job_id = ? and name = ? and %s and %s"""
          .formatted(COMPENSABLE_STEP, JOB_LOCKED);

  // The step keeps its state: its compensation runs again in the job's next compensation attempt.
  // (A child job step keeps the error of its ended child, the failure is in the history.)
  private static final String FAIL_COMPENSATION_SQL =
      """
            update bikesched_steps set last_error = case when kind = 'CHILD_JOB' then last_error else ? end
             where job_id = ? and name = ? and %s and %s
            returning state"""
          .formatted(COMPENSABLE_STEP, JOB_LOCKED);

  private static final String FAIL_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', last_error = ?, locked_by = null, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  // A step whose attempts are used up and that has a failure handler: FAILING until the handler
  // ran, so a handler that fails is run again (FAILING is not RUNNING: no lock holder).
  private static final String MARK_STEP_FAILING_SQL =
      """
            update bikesched_steps
               set state = 'FAILING', last_error = ?, locked_by = null, failure_attempts = 0
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  // A run of the failure handler of a FAILING step starts: counted before it runs, so a run that
  // crashes the node counts too.
  private static final String START_FAILURE_HANDLER_SQL =
      """
            update bikesched_steps set failure_attempts = failure_attempts + 1
             where job_id = ? and name = ? and state = 'FAILING' and %s
            returning failure_attempts"""
          .formatted(JOB_LOCKED);

  // The failure handler resolved the FAILING step with a fallback result: SUCCEEDED, but never
  // compensated (its side effect failed). The error stays for reference.
  private static final String RESOLVE_FAILING_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, compensable = false, finished_at = now()
             where job_id = ? and name = ? and state = 'FAILING' and %s"""
          .formatted(JOB_LOCKED);

  private static final String FAIL_FAILING_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', finished_at = now()
             where job_id = ? and name = ? and state = 'FAILING' and %s"""
          .formatted(JOB_LOCKED);

  // A wait or poll that timed out and has a failure handler: FAILING like a step whose attempts are
  // used up. finished_at is set again once the handler ran.
  private static final String MARK_TIMED_OUT_STEP_FAILING_SQL =
      """
            update bikesched_steps
               set state = 'FAILING', last_error = ?, failure_attempts = 0, finished_at = null
             where job_id = ? and name = ? and state = 'TIMED_OUT' and %s"""
          .formatted(JOB_LOCKED);

  private static final String FIND_FAILING_ERROR_SQL =
      """
            select coalesce(last_error, '') from bikesched_steps
             where job_id = ? and name = ? and kind = ? and state = 'FAILING'""";

  private static final String IS_JOB_LOCKED_SQL = "select " + JOB_LOCKED;

  // Ends a RUNNING attempt whose handler suspended at a waiting step (an event wait, a sleep or a
  // wait for a child job): WAITING until the event arrives (the child ends) or wait_until
  // (scheduled_at; 'infinity' for a wait without deadline) has passed. If the step is no longer
  // WAITING (the event was published, the child ended since), the job is scheduled right away, as
  // is a job whose cancellation was requested (to cancel it). Suspending does not use up an
  // attempt.
  private static final String SUSPEND_JOB_SQL =
      """
            update bikesched_jobs j
               set state = case when s.state = 'WAITING' then 'WAITING' else 'SCHEDULED' end,
                   scheduled_at = case when s.state = 'WAITING' and j.cancel_requested_at is null
                                       then coalesce(s.wait_until, 'infinity')
                                       else now() end,
                   attempts = j.attempts - 1, locked_by = null, lock_token = null, locked_at = null, updated_at = now()
              from bikesched_steps s
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING'
               and s.job_id = j.id and s.name = ?
            returning j.state, s.wait_until, j.attempts, j.type,
                      case when j.scheduled_at < 'infinity' then j.scheduled_at end""";

  // Job.postpone in a step's handler: the step keeps no result and gives its attempt back, so the
  // next attempt starts it again (START_STEP_SQL) with the same attempt number.
  private static final String POSTPONE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'POSTPONED', attempts = attempts - 1, locked_by = null, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  // Job.postpone: the job is due again after the delay (right away if its cancellation was
  // requested meanwhile, like a suspension) and gives its attempt back.
  private static final String POSTPONE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED',
                   scheduled_at = case when cancel_requested_at is null
                                       then now() + ? * interval '1 millisecond' else now() end,
                   attempts = attempts - 1, locked_by = null, lock_token = null, locked_at = null, updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING'
            returning scheduled_at, attempts, type, cancel_requested_at is not null""";

  // A step of a job, for a sleep.
  private static final String FIND_SLEEP_STEP_SQL =
      """
            select kind, state, wait_until, wait_until <= now()
              from bikesched_steps where job_id = ? and name = ? for update""";

  // Starts a sleep: WAITING until wait_until, or SUCCEEDED right away if that has passed.
  private static final String START_SLEEP_SQL =
      """
            insert into bikesched_steps (job_id, name, kind, state, attempts, wait_until, finished_at)
            select ?, ?, 'SLEEP', case when t.at <= now() then 'SUCCEEDED' else 'WAITING' end, 1, t.at,
                   case when t.at <= now() then now() end
              from (select coalesce(?::timestamptz, now() + ? * interval '1 millisecond') as at) t
            returning state, wait_until""";

  // The step of a job waiting for one of its children; locked (FIND_CHILD_WAIT_SQL) when waiting:
  // the child's end (JobStore) updates the same row, so a wait serializes with it.
  private static final String FIND_CHILD_WAIT_READ_ONLY_SQL =
      """
            select name, state, child_state, result::text, last_error, wait_until, wait_until <= now()
              from bikesched_steps where job_id = ? and child_job_id = ? and kind = 'CHILD_JOB'""";

  private static final String FIND_CHILD_WAIT_SQL = FIND_CHILD_WAIT_READ_ONLY_SQL + " for update";

  private static final String SET_CHILD_WAIT_DEADLINE_SQL =
      """
            update bikesched_steps set wait_until = now() + ? * interval '1 millisecond'
             where job_id = ? and name = ? and state = 'WAITING'
            returning wait_until""";

  private static final String TIME_OUT_CHILD_WAIT_SQL =
      """
            update bikesched_steps set state = 'TIMED_OUT', finished_at = now()
             where job_id = ? and name = ? and state = 'WAITING'""";

  private static final String FIND_CHILD_JOB_STEP_SQL =
      """
            select kind, state, child_job_id, attempts from bikesched_steps where job_id = ? and name = ?""";

  private static final String LOCK_STEP_SQL =
      """
            select state from bikesched_steps where job_id = ? and name = ? for update""";

  private static final String WAKE_UP_SQL =
      """
            update bikesched_steps set state = 'SUCCEEDED', finished_at = now()
             where job_id = ? and name = ? and state = 'WAITING'""";

  // A step of a job, for a poll.
  private static final String FIND_POLL_STEP_SQL =
      """
            select kind, state, result::text, attempts, wait_until, wait_until <= now(),
                   coalesce(last_error, '')
              from bikesched_steps where job_id = ? and name = ? for update""";

  // Starts the first check of a poll; its deadline (timeout_at) is null without a timeout.
  private static final String START_POLL_SQL =
      """
            insert into bikesched_steps (job_id, name, kind, state, attempts, locked_by, timeout_at)
            values (?, ?, 'POLL', 'RUNNING', 1, ?, now() + ? * interval '1 millisecond')
            returning attempts""";

  // Starts a further check of a poll; the deadline set by an earlier check wins.
  private static final String RESTART_POLL_SQL =
      """
            update bikesched_steps
               set state = 'RUNNING', attempts = attempts + 1, locked_by = ?, started_at = now(),
                   finished_at = null,
                   timeout_at = coalesce(timeout_at, now() + ? * interval '1 millisecond')
             where job_id = ? and name = ?
            returning attempts""";

  // Ends a check that was not ready: WAITING until the next check (at most until the deadline,
  // so the last check runs at the deadline), or TIMED_OUT if the deadline has passed.
  private static final String WAIT_POLL_SQL =
      """
            update bikesched_steps
               set state = case when timeout_at <= now() then 'TIMED_OUT' else 'WAITING' end,
                   wait_until = case when timeout_at <= now() then wait_until
                                     else least(now() + ? * interval '1 millisecond', timeout_at) end,
                   finished_at = case when timeout_at <= now() then now() end,
                   locked_by = null, last_error = null
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s
            returning state, wait_until, attempts"""
          .formatted(JOB_LOCKED);

  /**
   * A step as stored.
   *
   * @param kind what the step does: {@code STEP}, {@code SLEEP}, {@code EVENT_WAIT}, {@code
   *     EVENT_PUBLISH} or {@code CHILD_JOB}
   * @param state its state, e.g. {@code SUCCEEDED}, {@code WAITING} or {@code TIMED_OUT}
   * @param result its stored result (an event wait: a copy of the received event's payload) as JSON
   *     text
   * @param attempts its number of attempts
   * @param compensable whether it has a compensation to run if its job is cancelled or fails
   */
  public record StoredStep(
      StepKind kind, StepState state, String result, int attempts, boolean compensable) {}

  /**
   * Looks up a step of a job.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the step, or empty if it has not started yet
   */
  public Optional<StoredStep> findStep(UUID jobId, String name) {
    return access.withConnection(
        db ->
            db.one(
                FIND_STEP_SQL,
                row ->
                    new StoredStep(
                        row.enumValue(1, StepKind.class),
                        row.enumValue(2, StepState.class),
                        row.string(3),
                        row.integer(4),
                        row.bool(5)),
                jobId,
                name),
        e ->
            new BikeschedException(
                "Reading step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * A step that completed in an earlier attempt.
   *
   * @param kind what the step does, see {@link StoredStep}
   * @param result its stored result as JSON text, may be {@code null}
   * @param compensated whether its compensation ran already
   * @param compensable whether it has a compensation; not for a step its failure handler resolved
   * @param attempts the number of attempts it took
   */
  public record CompletedStep(
      StepKind kind,
      @Nullable String result,
      boolean compensated,
      boolean compensable,
      int attempts) {}

  /**
   * A step of a job starting a child job.
   *
   * @param kind what the step does, see {@link StoredStep}
   * @param state its state
   * @param childJobId the child it started, {@code null} unless the step is a {@code CHILD_JOB}
   * @param attempts its number of attempts (starts of a child)
   */
  public record ChildJobStep(
      StepKind kind, StepState state, @Nullable UUID childJobId, int attempts) {}

  /**
   * Looks up a step of a job, with the child job it started.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the step, or empty if it has not started yet
   */
  public Optional<ChildJobStep> findChildJobStep(UUID jobId, String name) {
    return access.withConnection(
        db ->
            db.one(
                FIND_CHILD_JOB_STEP_SQL,
                row ->
                    new ChildJobStep(
                        row.enumValue(1, StepKind.class),
                        row.enumValue(2, StepState.class),
                        row.uuid(3),
                        row.integer(4)),
                jobId,
                name),
        e ->
            new BikeschedException(
                "Reading step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /** A job's wait for one of its children, by the state of the step that started the child. */
  public sealed interface ChildWait {

    /** The step that started the child. */
    String stepName();

    /**
     * The child ended ({@code SUCCEEDED} step): its outcome is stored with the step.
     *
     * @param childState the state the child ended in
     * @param result the child's result as JSON text, may be {@code null}
     * @param error the child's error or cancel message, may be {@code null}
     */
    record ChildEnded(
        String stepName, JobState childState, @Nullable String result, @Nullable String error)
        implements ChildWait {}

    /** The wait timed out ({@code TIMED_OUT} step). */
    record ChildTimedOut(String stepName) implements ChildWait {}

    /**
     * The step waits for the child.
     *
     * @param waitUntil the deadline of the wait, {@code null} if none
     */
    record ChildWaiting(String stepName, @Nullable Instant waitUntil) implements ChildWait {}

    /**
     * The step neither waits nor holds an outcome: only met while compensating ({@code CANCELLED},
     * {@code COMPENSATED}) or after the step was reset.
     *
     * @param state the step's state
     */
    record NotWaiting(String stepName, StepState state) implements ChildWait {}
  }

  /** A row of {@code FIND_CHILD_WAIT_SQL}. */
  private record ChildWaitStep(ChildWait childWait, boolean deadlinePassed) {

    static ChildWaitStep from(Sql.Row row) throws SQLException {
      String name = row.string(1);
      StepState state = row.enumValue(2, StepState.class);
      ChildWait wait =
          switch (state) {
            case SUCCEEDED ->
                new ChildWait.ChildEnded(
                    name, row.enumValue(3, JobState.class), row.string(4), row.string(5));
            case TIMED_OUT -> new ChildWait.ChildTimedOut(name);
            case WAITING -> new ChildWait.ChildWaiting(name, row.instant(6));
            case RUNNING, FAILING, FAILED, COMPENSATED, CANCELLED, POSTPONED ->
                new ChildWait.NotWaiting(name, state);
          };
      return new ChildWaitStep(wait, row.bool(7));
    }
  }

  /**
   * Waits for a child job: returns the outcome stored with the child's step if the child ended, or
   * the timed-out wait; otherwise stores the deadline on the first wait with a timeout (the stored
   * one wins on later waits), times the wait out once it has passed, or reports it waiting. The
   * database's clock decides.
   *
   * @param jobId the waiting job
   * @param lock the lock of the job's claim (node and token)
   * @param childJobId the child
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @return the wait
   * @throws IllegalArgumentException if the job has no step that started the child
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public ChildWait awaitChildJob(
      UUID jobId, JobLock lock, UUID childJobId, @Nullable Duration timeout) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          ChildWaitStep step =
              db.one(FIND_CHILD_WAIT_SQL, ChildWaitStep::from, jobId, childJobId)
                  .orElseThrow(() -> notAChild(jobId, childJobId));
          if (!(step.childWait() instanceof ChildWait.ChildWaiting wait)) {
            return step.childWait();
          }
          String name = wait.stepName();
          if (step.deadlinePassed()) {
            db.update(TIME_OUT_CHILD_WAIT_SQL, jobId, name);
            history.record(
                db, Change.step(jobId, name, "TIMED_OUT", null, "waiting for job " + childJobId));
            return new ChildWait.ChildTimedOut(name);
          }
          if (timeout != null && wait.waitUntil() == null) {
            Instant deadline =
                db.one(
                        SET_CHILD_WAIT_DEADLINE_SQL,
                        row -> row.instant(1),
                        Sql.millis(timeout),
                        jobId,
                        name)
                    .orElseThrow();
            history.record(
                db,
                Change.step(
                    jobId,
                    name,
                    "WAITING",
                    null,
                    "waiting for job " + childJobId + " until " + deadline));
            return new ChildWait.ChildWaiting(name, deadline);
          }
          return wait;
        },
        e ->
            new BikeschedException(
                "Waiting for job " + childJobId + " in bikesched job " + jobId + " failed", e));
  }

  /**
   * Reads a job's wait for one of its children without changing it (to replay it while
   * compensating).
   *
   * @param jobId the waiting job
   * @param childJobId the child
   * @return the wait as stored
   * @throws IllegalArgumentException if the job has no step that started the child
   */
  public ChildWait findChildWait(UUID jobId, UUID childJobId) {
    return access.withConnection(
        db ->
            db.one(FIND_CHILD_WAIT_READ_ONLY_SQL, ChildWaitStep::from, jobId, childJobId)
                .map(ChildWaitStep::childWait)
                .orElseThrow(() -> notAChild(jobId, childJobId)),
        e ->
            new BikeschedException(
                "Reading the wait for job " + childJobId + " of bikesched job " + jobId + " failed",
                e));
  }

  private static IllegalArgumentException notAChild(UUID jobId, UUID childJobId) {
    return new IllegalArgumentException(
        "Job "
            + childJobId
            + " is not a child of job "
            + jobId
            + ": a job can only wait for the jobs it started with job.newJob(…)");
  }

  /**
   * Ends a running attempt whose handler suspended at a wait for a child job, see {@code
   * SUSPEND_JOB_SQL}. Locks the step first: if the child ends concurrently, either the suspension
   * waits for the child's transaction and then finds the step completed (the job is due right
   * away), or the child's end waits for the suspension and then resumes the {@code WAITING} job.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the step waiting for the child
   * @param childJobId the child, for the history
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<SuspendedJob> suspendAtChildJob(
      UUID jobId, JobLock lock, String stepName, UUID childJobId) {
    return access.inTransaction(
        db -> {
          db.one(LOCK_STEP_SQL, row -> row.string(1), jobId, stepName);
          return suspend(db, history, jobId, lock, stepName, "waiting for job " + childJobId);
        },
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Looks up a step that succeeded in an earlier attempt of the job (and may have been compensated
   * since).
   *
   * @param jobId the job id
   * @param name the step name
   * @return the completed step with its result, or empty if the step has not succeeded yet
   */
  public Optional<CompletedStep> findCompletedStep(UUID jobId, String name) {
    return findStep(jobId, name)
        .flatMap(
            step ->
                switch (step.state()) {
                  case SUCCEEDED ->
                      Optional.of(
                          new CompletedStep(
                              step.kind(),
                              step.result(),
                              false,
                              step.compensable(),
                              step.attempts()));
                  case COMPENSATED ->
                      Optional.of(
                          new CompletedStep(
                              step.kind(),
                              step.result(),
                              true,
                              step.compensable(),
                              step.attempts()));
                  case RUNNING, WAITING, FAILING, FAILED, TIMED_OUT, CANCELLED, POSTPONED ->
                      Optional.empty();
                });
  }

  /**
   * Starts an attempt of a step: creates it as {@code RUNNING}, locked by the node, or, if an
   * earlier attempt did not succeed, sets it back to {@code RUNNING} and counts the attempt.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @return the attempt number of the step, or empty if the step has already succeeded
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public Optional<Integer> startStep(UUID jobId, JobLock lock, String name) {
    return history.change(
        db -> {
          Optional<Integer> attempt =
              db.one(
                  START_STEP_SQL,
                  row -> row.integer(1),
                  jobId,
                  name,
                  lock.nodeId(),
                  jobId,
                  lock.token());
          if (attempt.isEmpty()) {
            requireJobLock(db, jobId, lock);
            return attempt;
          }
          String detail = attempt.get() > 1 ? "step attempt " + attempt.get() : null;
          history.record(db, Change.step(jobId, name, "RUNNING", lock.nodeId(), detail));
          return attempt;
        },
        e ->
            new BikeschedException(
                "Starting step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void completeStep(UUID jobId, JobLock lock, String name, @Nullable String result) {
    completeStep(jobId, lock, name, result, false);
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   * @param compensable whether the step has a compensation: a cancelled or permanently failed job
   *     with such a step is compensated before it ends
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void completeStep(
      UUID jobId, JobLock lock, String name, @Nullable String result, boolean compensable) {
    complete(jobId, lock, name, result, compensable, null);
  }

  private void complete(
      UUID jobId,
      JobLock lock,
      String name,
      @Nullable String result,
      boolean compensable,
      @Nullable String detail) {
    history.change(
        db -> {
          int updated =
              db.update(
                  COMPLETE_STEP_SQL,
                  result,
                  compensable,
                  jobId,
                  name,
                  lock.nodeId(),
                  jobId,
                  lock.token());
          if (updated > 0) {
            history.record(db, Change.step(jobId, name, "SUCCEEDED", lock.nodeId(), detail));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Storing the result of step '" + name + "' of bikesched job " + jobId + " failed",
                e));
  }

  /**
   * Marks a succeeded step {@code COMPENSATED} once its compensation ran.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void compensateStep(UUID jobId, JobLock lock, String name) {
    history.change(
        db -> {
          if (db.update(COMPENSATE_STEP_SQL, jobId, name, jobId, lock.token()) > 0) {
            history.record(db, Change.step(jobId, name, "COMPENSATED", lock.nodeId(), null));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Recording the compensation of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * Records that compensating a step failed; it is compensated again in the next attempt.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param error description of the failure, stored as the step's {@code last_error}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void failCompensation(UUID jobId, JobLock lock, String name, String error) {
    history.change(
        db -> {
          String message = ValidationUtil.truncateMessage(error);
          Optional<String> state =
              db.one(
                  FAIL_COMPENSATION_SQL,
                  row -> row.string(1),
                  message,
                  jobId,
                  name,
                  jobId,
                  lock.token());
          if (state.isPresent()) {
            history.record(
                db,
                Change.step(
                    jobId, name, state.get(), lock.nodeId(), "compensation failed: " + error));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Recording the failed compensation of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * Marks a running step {@code FAILED}.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void failStep(UUID jobId, JobLock lock, String name, String error) {
    fail(jobId, lock, name, error, error);
  }

  /**
   * Marks a running step {@code FAILING}: its attempts are used up and its failure handler has yet
   * to run. A job that ends before the step is {@link #failFailingStep failed} runs the handler
   * again in its next attempt.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void markStepFailing(UUID jobId, JobLock lock, String name, String error) {
    history.change(
        db -> {
          String message = ValidationUtil.truncateMessage(error);
          if (db.update(
                  MARK_STEP_FAILING_SQL, message, jobId, name, lock.nodeId(), jobId, lock.token())
              > 0) {
            history.record(db, Change.step(jobId, name, "FAILING", lock.nodeId(), error));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Marking step '" + name + "' of bikesched job " + jobId + " as failing failed", e));
  }

  /**
   * Marks a {@code TIMED_OUT} event wait or poll {@code FAILING}: its failure handler has yet to
   * run. A job that ends before the step is {@link #failFailingStep failed} or {@link
   * #resolveFailingStep resolved} runs the handler again in its next attempt.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param error description of the timeout, stored as {@code last_error}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void markTimedOutStepFailing(UUID jobId, JobLock lock, String name, String error) {
    history.change(
        db -> {
          String message = ValidationUtil.truncateMessage(error);
          if (db.update(MARK_TIMED_OUT_STEP_FAILING_SQL, message, jobId, name, jobId, lock.token())
              > 0) {
            history.record(db, Change.step(jobId, name, "FAILING", lock.nodeId(), error));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Marking step '" + name + "' of bikesched job " + jobId + " as failing failed", e));
  }

  /**
   * Marks a {@code FAILING} step {@code FAILED} once its failure handler ran (or was given up).
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param detail recorded in the job history, {@code null} for none
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void failFailingStep(UUID jobId, JobLock lock, String name, @Nullable String detail) {
    history.change(
        db -> {
          if (db.update(FAIL_FAILING_STEP_SQL, jobId, name, jobId, lock.token()) > 0) {
            history.record(db, Change.step(jobId, name, "FAILED", lock.nodeId(), detail));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Recording the failure of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * Counts a run of the failure handler of a {@code FAILING} step, before it runs.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @return the number of runs including this one; empty if the step is not {@code FAILING}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public Optional<Integer> startFailureHandler(UUID jobId, JobLock lock, String name) {
    return access.withConnection(
        db -> {
          Optional<Integer> run =
              db.one(
                  START_FAILURE_HANDLER_SQL,
                  row -> row.integer(1),
                  jobId,
                  name,
                  jobId,
                  lock.token());
          if (run.isEmpty()) {
            requireJobLock(db, jobId, lock);
          }
          return run;
        },
        e ->
            new BikeschedException(
                "Starting the failure handler of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * Marks a {@code FAILING} step {@code SUCCEEDED} with the fallback result its failure handler
   * resolved it with; it is not compensable.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void resolveFailingStep(UUID jobId, JobLock lock, String name, @Nullable String result) {
    history.change(
        db -> {
          if (db.update(RESOLVE_FAILING_STEP_SQL, result, jobId, name, jobId, lock.token()) > 0) {
            history.record(
                db,
                Change.step(
                    jobId, name, "SUCCEEDED", lock.nodeId(), "resolved by its failure handler"));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Resolving step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * The error of a {@code FAILING} step, whose failure handler has to run (again).
   *
   * @param jobId the job id
   * @param name the step name
   * @param kind the kind of step, see {@link StoredStep}
   * @return the stored error, or empty if the step is not {@code FAILING} or of another kind
   */
  public Optional<String> findFailingError(UUID jobId, String name, StepKind kind) {
    return access.withConnection(
        db -> db.one(FIND_FAILING_ERROR_SQL, row -> row.string(1), jobId, name, kind.name()),
        e ->
            new BikeschedException(
                "Reading step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  private void fail(UUID jobId, JobLock lock, String name, String error, String detail) {
    history.change(
        db -> {
          String message = ValidationUtil.truncateMessage(error);
          if (db.update(FAIL_STEP_SQL, message, jobId, name, lock.nodeId(), jobId, lock.token())
              > 0) {
            history.record(db, Change.step(jobId, name, "FAILED", lock.nodeId(), detail));
          } else {
            requireJobLock(db, jobId, lock);
          }
          return null;
        },
        e ->
            new BikeschedException(
                "Recording the failure of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }

  /**
   * A sleep step after {@link #sleep}.
   *
   * @param awake {@code true} if the sleep is over (the step {@code SUCCEEDED}), {@code false} if
   *     the step is {@code WAITING}
   * @param wakeUpAt when the job wakes up (stored with the step on the first run)
   */
  public record Sleep(boolean awake, Instant wakeUpAt) {}

  /**
   * Sleeps as a step: starts the sleep on the first run ({@code WAITING} until the wake-up time, or
   * {@code SUCCEEDED} if that has passed), completes it once its wake-up time has passed, and
   * otherwise reports it still sleeping. The wake-up time stored on the first run wins; exactly one
   * of {@code duration} and {@code until} is given. The database's clock decides.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param duration how long to sleep, counted from the first run; {@code null} if {@code until}
   * @param until when to wake up; {@code null} if {@code duration}
   * @return whether the sleep is over, and the wake-up time
   * @throws IllegalStateException if the step name belongs to another kind of step
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public Sleep sleep(
      UUID jobId, JobLock lock, String name, @Nullable Duration duration, @Nullable Instant until) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          Optional<SleepStep> step =
              db.one(
                  FIND_SLEEP_STEP_SQL,
                  row ->
                      new SleepStep(
                          row.enumValue(1, StepKind.class),
                          row.enumValue(2, StepState.class),
                          row.instant(3),
                          row.bool(4)),
                  jobId,
                  name);
          if (step.isPresent()) {
            SleepStep found = step.get();
            StepKind.SLEEP.require(name, jobId, found.kind());
            return switch (found.state()) {
              case SUCCEEDED -> new Sleep(true, found.wakeUpAt());
              case WAITING -> {
                if (!found.due()) {
                  yield new Sleep(false, found.wakeUpAt());
                }
                db.update(WAKE_UP_SQL, jobId, name);
                history.record(db, Change.step(jobId, name, "SUCCEEDED", null, "woke up"));
                yield new Sleep(true, found.wakeUpAt());
              }
              case RUNNING, FAILING, FAILED, TIMED_OUT, COMPENSATED, CANCELLED, POSTPONED ->
                  throw new IllegalStateException(
                      "Sleep step '" + name + "' of job " + jobId + " is " + found.state());
            };
          }
          Sleep started =
              db.one(
                      START_SLEEP_SQL,
                      row ->
                          new Sleep(
                              row.enumValue(1, StepState.class) == StepState.SUCCEEDED,
                              row.instant(2)),
                      jobId,
                      name,
                      Sql.timestamp(until),
                      Sql.millis(duration))
                  .orElseThrow();
          history.record(
              db,
              Change.step(
                  jobId,
                  name,
                  started.awake() ? "SUCCEEDED" : "WAITING",
                  null,
                  (started.awake()
                      ? "wake-up time " + started.wakeUpAt() + " already passed"
                      : "sleeping until " + started.wakeUpAt())));
          return started;
        },
        e ->
            new BikeschedException(
                "Sleeping in step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /** A poll step after {@link #startPoll}. */
  public sealed interface PollStep {

    /**
     * A check started ({@code RUNNING}): the first one, a due one, or one after a failed check.
     *
     * @param checks the number of checks so far, including the started one
     * @param resumed whether the poll was waiting when this run reached it (the job was claimed for
     *     a further check)
     */
    record CheckStarted(int checks, boolean resumed) implements PollStep {}

    /**
     * The next check is not due yet ({@code WAITING}).
     *
     * @param checks the number of checks so far
     * @param waitUntil the time of the next check
     */
    record NotDue(int checks, Instant waitUntil) implements PollStep {}

    /**
     * Ready in an earlier run ({@code SUCCEEDED}).
     *
     * @param result the ready value as JSON text, may be {@code null}
     */
    record Ready(@Nullable String result) implements PollStep {}

    /**
     * Timed out ({@code TIMED_OUT}).
     *
     * @param checks the number of checks it took
     */
    record PollTimedOut(int checks) implements PollStep {}

    /**
     * Its failure handler has to run again ({@code FAILING}).
     *
     * @param error the stored error, handed to the failure handler
     */
    record PollFailing(String error) implements PollStep {}
  }

  /**
   * Reaches a poll: starts a check (the first one, or a further one once it is due or after a
   * failed check), or reports the poll as stored if it is ready, timed out, or not due yet. The
   * deadline is stored on the first check with a timeout; it wins on later checks.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param timeout how long to poll at most, counted from the first check; {@code null} for no
   *     limit
   * @return the poll
   * @throws IllegalStateException if the step name belongs to another kind of step
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public PollStep startPoll(UUID jobId, JobLock lock, String name, @Nullable Duration timeout) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          Optional<StoredPoll> step =
              db.one(
                  FIND_POLL_STEP_SQL,
                  row ->
                      new StoredPoll(
                          row.enumValue(1, StepKind.class),
                          row.enumValue(2, StepState.class),
                          row.string(3),
                          row.integer(4),
                          row.instant(5),
                          row.bool(6),
                          row.string(7)),
                  jobId,
                  name);
          if (step.isEmpty()) {
            int checks =
                db.one(
                        START_POLL_SQL,
                        row -> row.integer(1),
                        jobId,
                        name,
                        lock.nodeId(),
                        Sql.millis(timeout))
                    .orElseThrow();
            return new PollStep.CheckStarted(checks, false);
          }
          StoredPoll found = step.get();
          StepKind.POLL.require(name, jobId, found.kind());
          boolean waiting = found.state() == StepState.WAITING;
          return switch (found.state()) {
            case SUCCEEDED -> new PollStep.Ready(found.result());
            case TIMED_OUT -> new PollStep.PollTimedOut(found.checks());
            case FAILING -> new PollStep.PollFailing(found.error());
            case WAITING, FAILED, RUNNING -> {
              if (waiting && !found.due()) {
                yield new PollStep.NotDue(found.checks(), found.waitUntil());
              }
              int checks =
                  db.one(
                          RESTART_POLL_SQL,
                          row -> row.integer(1),
                          lock.nodeId(),
                          Sql.millis(timeout),
                          jobId,
                          name)
                      .orElseThrow();
              yield new PollStep.CheckStarted(checks, waiting);
            }
            case COMPENSATED, CANCELLED, POSTPONED ->
                throw new IllegalStateException(
                    "Poll step '" + name + "' of job " + jobId + " is " + found.state());
          };
        },
        e ->
            new BikeschedException(
                "Polling in step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * Marks a running poll {@code SUCCEEDED} after a check found it ready, and stores the value.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param result the ready value as JSON text, may be {@code null}
   * @param checks the number of checks it took, for the history
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void completePoll(
      UUID jobId, JobLock lock, String name, @Nullable String result, int checks) {
    complete(jobId, lock, name, result, false, "ready after " + checks(checks));
  }

  /**
   * Marks a running poll {@code FAILED} after its check threw.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   * @param check the number of the failed check, for the history
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void failPoll(UUID jobId, JobLock lock, String name, String error, int check) {
    fail(jobId, lock, name, error, "check " + check + " failed: " + error);
  }

  /**
   * A poll after a check that was not ready.
   *
   * @param timedOut whether its deadline has passed (the step is {@code TIMED_OUT})
   * @param waitUntil the time of the next check, if not timed out
   * @param checks the number of checks so far
   */
  public record PollWait(boolean timedOut, Instant waitUntil, int checks) {}

  /**
   * Ends a check that was not ready: the poll waits until the next check (at most until its
   * deadline), or times out if the deadline has passed. Only a timeout is recorded in the history.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param delay the time until the next check
   * @return the poll
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public PollWait waitPoll(UUID jobId, JobLock lock, String name, Duration delay) {
    return history.change(
        db -> {
          Optional<PollWait> wait =
              db.one(
                  WAIT_POLL_SQL,
                  row ->
                      new PollWait(
                          row.enumValue(1, StepState.class) == StepState.TIMED_OUT,
                          row.instant(2),
                          row.integer(3)),
                  Sql.millis(delay),
                  jobId,
                  name,
                  lock.nodeId(),
                  jobId,
                  lock.token());
          if (wait.isEmpty()) {
            requireJobLock(db, jobId, lock);
            throw new IllegalStateException(
                "Poll step '" + name + "' of job " + jobId + " is not running");
          }
          if (wait.get().timedOut()) {
            history.record(
                db,
                Change.step(
                    jobId,
                    name,
                    "TIMED_OUT",
                    lock.nodeId(),
                    "timed out after " + checks(wait.get().checks())));
          }
          return wait.get();
        },
        e ->
            new BikeschedException(
                "Recording a check of poll '" + name + "' of bikesched job " + jobId + " failed",
                e));
  }

  private static String checks(int checks) {
    return checks == 1 ? "1 check" : checks + " checks";
  }

  /** A row of {@code FIND_POLL_STEP_SQL}. */
  private record StoredPoll(
      StepKind kind,
      StepState state,
      String result,
      int checks,
      Instant waitUntil,
      boolean due,
      String error) {}

  /**
   * A job after suspending it.
   *
   * @param waiting {@code true} if it is {@code WAITING}, {@code false} if it was scheduled right
   *     away (the event arrived meanwhile, or its cancellation was requested)
   * @param waitUntil the deadline of an event wait ({@code null} if none) or the wake-up time of a
   *     sleep
   */
  public record SuspendedJob(boolean waiting, @Nullable Instant waitUntil) {}

  /**
   * Ends a running attempt whose handler suspended at a sleeping step, see {@code SUSPEND_JOB_SQL}.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the sleeping step
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<SuspendedJob> suspendJob(UUID jobId, JobLock lock, String stepName) {
    return access.inTransaction(
        db -> suspend(db, history, jobId, lock, stepName, null),
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Ends a running attempt whose handler suspended at a poll until its next check, see {@code
   * SUSPEND_JOB_SQL}. Only the first suspension of a poll is recorded in the history: a job claimed
   * while its poll waits is not recorded either (see {@code JobStore.claimJobs}), so the checks in
   * between leave no entries. A suspension that schedules the job right away is always recorded.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the polling step
   * @param recorded whether to record the suspension: {@code false} if the poll was already waiting
   *     when the job was claimed
   * @return the job after suspending; empty if the job is not running on this node (anymore)
   */
  public Optional<SuspendedJob> suspendAtPoll(
      UUID jobId, JobLock lock, String stepName, boolean recorded) {
    return access.inTransaction(
        db -> suspend(db, history, jobId, lock, stepName, "polling", recorded),
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Suspends the job at a waiting step in the caller's transaction, see {@code SUSPEND_JOB_SQL}.
   *
   * @param waitingFor what the step waits for, for the history; {@code null} to leave it out
   */
  static Optional<SuspendedJob> suspend(
      Sql db,
      HistoryStore history,
      UUID jobId,
      JobLock lock,
      String stepName,
      @Nullable String waitingFor)
      throws SQLException {
    return suspend(db, history, jobId, lock, stepName, waitingFor, true);
  }

  private static Optional<SuspendedJob> suspend(
      Sql db,
      HistoryStore history,
      UUID jobId,
      JobLock lock,
      String stepName,
      @Nullable String waitingFor,
      boolean recorded)
      throws SQLException {
    Optional<Suspension> suspended =
        db.one(
            SUSPEND_JOB_SQL,
            row ->
                new Suspension(
                    row.enumValue(1, JobState.class),
                    row.instant(2),
                    row.integer(3),
                    row.string(4),
                    row.instant(5)),
            jobId,
            lock.token(),
            stepName);
    if (suspended.isEmpty()) {
      return Optional.empty();
    }
    Suspension suspension = suspended.get();
    if (suspension.dueAt() != null) {
      // Due again at the deadline or wake-up time, or right away: the node wakes itself up.
      db.dueAt(suspension.type(), suspension.dueAt());
    }
    boolean waiting = suspension.state() == JobState.WAITING;
    if (!recorded && waiting) {
      return Optional.of(new SuspendedJob(true, suspension.waitUntil()));
    }
    history.record(
        db,
        Change.job(
            jobId,
            suspension.state().name(),
            suspension.attempts(),
            lock.nodeId(),
            "suspended at step '"
                + stepName
                + "'"
                + (waitingFor == null ? "" : ", " + waitingFor)
                + (waiting ? "" : ", due again right away")));
    return Optional.of(new SuspendedJob(waiting, suspension.waitUntil()));
  }

  /**
   * A job after postponing it.
   *
   * @param scheduledAt when it is due again
   * @param cancelRequested whether its cancellation was requested meanwhile: then it is due now
   */
  public record PostponedJob(Instant scheduledAt, boolean cancelRequested) {}

  /**
   * Ends a running attempt whose handler postponed the job ({@code Job.postpone}): the job is
   * {@code SCHEDULED} again after the delay with its attempt given back, see {@code
   * POSTPONE_JOB_SQL}; a step whose handler postponed it becomes {@code POSTPONED}, see {@code
   * POSTPONE_STEP_SQL}. One transaction, fenced by the job lock.
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param stepName the step whose handler postponed the job, {@code null} if none
   * @param delay how long from now the job is due again
   * @return the job after postponing; empty if the job is not running on this node (anymore)
   */
  public Optional<PostponedJob> postponeJob(
      UUID jobId, JobLock lock, @Nullable String stepName, Duration delay) {
    return access.inTransaction(
        db -> {
          List<Change> changes = new ArrayList<>();
          if (stepName != null) {
            int postponed =
                db.update(POSTPONE_STEP_SQL, jobId, stepName, lock.nodeId(), jobId, lock.token());
            if (postponed == 0) {
              return Optional.<PostponedJob>empty();
            }
          }
          Optional<Postponement> job =
              db.one(
                  POSTPONE_JOB_SQL,
                  row ->
                      new Postponement(row.instant(1), row.integer(2), row.string(3), row.bool(4)),
                  Sql.millis(delay),
                  jobId,
                  lock.token());
          if (job.isEmpty()) {
            return Optional.<PostponedJob>empty();
          }
          Postponement postponement = job.get();
          // Due again after the delay, or right away: the node wakes itself up.
          db.dueAt(postponement.type(), postponement.scheduledAt());
          String until = "until " + postponement.scheduledAt();
          if (stepName != null) {
            changes.add(Change.step(jobId, stepName, "POSTPONED", lock.nodeId(), until));
          }
          changes.add(
              Change.job(
                  jobId,
                  "SCHEDULED",
                  postponement.attempts(),
                  lock.nodeId(),
                  "postponed"
                      + (stepName == null ? "" : " in step '" + stepName + "'")
                      + " "
                      + until
                      + (postponement.cancelRequested() ? ", due again right away" : "")));
          history.record(db, changes);
          return Optional.of(
              new PostponedJob(postponement.scheduledAt(), postponement.cancelRequested()));
        },
        e -> new BikeschedException("Postponing bikesched job " + jobId + " failed", e));
  }

  /** A row of {@code POSTPONE_JOB_SQL}. */
  private record Postponement(
      Instant scheduledAt, int attempts, String type, boolean cancelRequested) {}

  /** A row of {@code SUSPEND_JOB_SQL}. */
  private record Suspension(
      JobState state, Instant waitUntil, int attempts, String type, Instant dueAt) {}

  /** A row of {@code FIND_SLEEP_STEP_SQL}. */
  private record SleepStep(StepKind kind, StepState state, Instant wakeUpAt, boolean due) {}

  /**
   * After a fenced write changed nothing: throws if the job is no longer running on the node
   * (otherwise the step simply was not in the expected state, e.g. it already succeeded).
   *
   * @throws JobLockLostException if the job is no longer running on the node
   */
  static void requireJobLock(Sql db, UUID jobId, JobLock lock) throws SQLException {
    if (!db.one(IS_JOB_LOCKED_SQL, row -> row.bool(1), jobId, lock.token()).orElse(false)) {
      throw new JobLockLostException(jobId, lock.nodeId());
    }
  }
}
