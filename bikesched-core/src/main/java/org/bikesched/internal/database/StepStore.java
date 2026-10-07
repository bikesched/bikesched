// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.ValidationUtil;

/**
 * The {@code bikesched_steps} table: durable steps of jobs, sleeps, waits for child jobs, and
 * suspending a job at a waiting step (event steps are handled by {@link EventStore}, starting child
 * jobs by {@link JobStore}). Changes are recorded in the {@link HistoryStore job history}, if
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
    this.access = Objects.requireNonNull(access, "access");
    this.history = Objects.requireNonNull(history, "history");
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
            select kind, state, result::text, attempts from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a regular step that has not succeeded yet; returns its attempt number.
  // Returns no row if the step already succeeded (e.g. concurrently), was compensated since, is
  // another kind of step, or the job is no longer running on this node.
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, locked_by, started_at)
            select ?, ?, 'RUNNING', 1, ?, now()
             where %s
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1,
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
            returning j.state, s.wait_until, j.attempts""";

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

  /**
   * A step as stored.
   *
   * @param kind what the step does: {@code STEP}, {@code SLEEP}, {@code EVENT_WAIT}, {@code
   *     EVENT_PUBLISH} or {@code CHILD_JOB}
   * @param state its state, e.g. {@code SUCCEEDED}, {@code WAITING} or {@code TIMED_OUT}
   * @param result its stored result (an event wait: a copy of the received event's payload) as JSON
   *     text
   * @param attempts its number of attempts
   */
  public record StoredStep(String kind, String state, String result, int attempts) {}

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
                row -> new StoredStep(row.string(1), row.string(2), row.string(3), row.integer(4)),
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
   * @param attempts the number of attempts it took
   */
  public record CompletedStep(String kind, String result, boolean compensated, int attempts) {}

  /**
   * A step of a job starting a child job.
   *
   * @param kind what the step does, see {@link StoredStep}
   * @param state its state
   * @param childJobId the child it started, {@code null} unless the step is a {@code CHILD_JOB}
   * @param attempts its number of attempts (starts of a child)
   */
  public record ChildJobStep(String kind, String state, UUID childJobId, int attempts) {}

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
                row -> new ChildJobStep(row.string(1), row.string(2), row.uuid(3), row.integer(4)),
                jobId,
                name),
        e ->
            new BikeschedException(
                "Reading step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * A job's wait for one of its children.
   *
   * @param stepName the step that started the child
   * @param state {@code SUCCEEDED} (the child ended), {@code WAITING}, {@code TIMED_OUT}, or (only
   *     met while compensating) {@code CANCELLED} or {@code COMPENSATED}
   * @param childState the state the child ended in, {@code null} unless {@code SUCCEEDED}
   * @param result the child's result as JSON text, may be {@code null}
   * @param error the child's error or cancel message, may be {@code null}
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  public record ChildWait(
      String stepName,
      String state,
      String childState,
      String result,
      String error,
      Instant waitUntil) {}

  /** A row of {@code FIND_CHILD_WAIT_SQL}. */
  private record ChildWaitStep(ChildWait childWait, boolean deadlinePassed) {

    static ChildWaitStep from(Sql.Row row) throws SQLException {
      return new ChildWaitStep(
          new ChildWait(
              row.string(1),
              row.string(2),
              row.string(3),
              row.string(4),
              row.string(5),
              row.instant(6)),
          row.bool(7));
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
  public ChildWait awaitChildJob(UUID jobId, JobLock lock, UUID childJobId, Duration timeout) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          ChildWaitStep step =
              db.one(FIND_CHILD_WAIT_SQL, ChildWaitStep::from, jobId, childJobId)
                  .orElseThrow(() -> notAChild(jobId, childJobId));
          ChildWait wait = step.childWait();
          if (!wait.state().equals("WAITING")) {
            return wait;
          }
          String name = wait.stepName();
          if (step.deadlinePassed()) {
            db.update(TIME_OUT_CHILD_WAIT_SQL, jobId, name);
            history.record(
                db, Change.step(jobId, name, "TIMED_OUT", null, "waiting for job " + childJobId));
            return new ChildWait(name, "TIMED_OUT", null, null, null, wait.waitUntil());
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
            return new ChildWait(name, "WAITING", null, null, null, deadline);
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
                  case "SUCCEEDED" ->
                      Optional.of(
                          new CompletedStep(step.kind(), step.result(), false, step.attempts()));
                  case "COMPENSATED" ->
                      Optional.of(
                          new CompletedStep(step.kind(), step.result(), true, step.attempts()));
                  default -> Optional.empty();
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
  public void completeStep(UUID jobId, JobLock lock, String name, String result) {
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
      UUID jobId, JobLock lock, String name, String result, boolean compensable) {
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
            history.record(db, Change.step(jobId, name, "SUCCEEDED", lock.nodeId(), null));
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
    history.change(
        db -> {
          String message = ValidationUtil.truncateMessage(error);
          if (db.update(FAIL_STEP_SQL, message, jobId, name, lock.nodeId(), jobId, lock.token())
              > 0) {
            history.record(db, Change.step(jobId, name, "FAILED", lock.nodeId(), error));
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
  public Sleep sleep(UUID jobId, JobLock lock, String name, Duration duration, Instant until) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          Optional<SleepStep> step =
              db.one(
                  FIND_SLEEP_STEP_SQL,
                  row -> new SleepStep(row.string(1), row.string(2), row.instant(3), row.bool(4)),
                  jobId,
                  name);
          if (step.isPresent()) {
            SleepStep found = step.get();
            if (!found.kind().equals("SLEEP")) {
              throw new IllegalStateException(
                  "Step '"
                      + name
                      + "' of job "
                      + jobId
                      + " is "
                      + describeKind(found.kind())
                      + ", not a sleep; step names must be unique within a job");
            }
            return switch (found.state()) {
              case "SUCCEEDED" -> new Sleep(true, found.wakeUpAt());
              case "WAITING" -> {
                if (!found.due()) {
                  yield new Sleep(false, found.wakeUpAt());
                }
                db.update(WAKE_UP_SQL, jobId, name);
                history.record(db, Change.step(jobId, name, "SUCCEEDED", null, "woke up"));
                yield new Sleep(true, found.wakeUpAt());
              }
              default ->
                  throw new IllegalStateException(
                      "Sleep step '" + name + "' of job " + jobId + " is " + found.state());
            };
          }
          Sleep started =
              db.one(
                      START_SLEEP_SQL,
                      row -> new Sleep(row.string(1).equals("SUCCEEDED"), row.instant(2)),
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

  /**
   * A job after suspending it.
   *
   * @param waiting {@code true} if it is {@code WAITING}, {@code false} if it was scheduled right
   *     away (the event arrived meanwhile, or its cancellation was requested)
   * @param waitUntil the deadline of an event wait ({@code null} if none) or the wake-up time of a
   *     sleep
   */
  public record SuspendedJob(boolean waiting, Instant waitUntil) {}

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
   * Suspends the job at a waiting step in the caller's transaction, see {@code SUSPEND_JOB_SQL}.
   *
   * @param waitingFor what the step waits for, for the history; {@code null} to leave it out
   */
  static Optional<SuspendedJob> suspend(
      Sql db, HistoryStore history, UUID jobId, JobLock lock, String stepName, String waitingFor)
      throws SQLException {
    Optional<Suspension> suspended =
        db.one(
            SUSPEND_JOB_SQL,
            row -> new Suspension(row.string(1), row.instant(2), row.integer(3)),
            jobId,
            lock.token(),
            stepName);
    if (suspended.isEmpty()) {
      return Optional.empty();
    }
    Suspension suspension = suspended.get();
    boolean waiting = suspension.state().equals("WAITING");
    history.record(
        db,
        Change.job(
            jobId,
            suspension.state(),
            suspension.attempts(),
            lock.nodeId(),
            "suspended at step '"
                + stepName
                + "'"
                + (waitingFor == null ? "" : ", " + waitingFor)
                + (waiting ? "" : ", due again right away")));
    return Optional.of(new SuspendedJob(waiting, suspension.waitUntil()));
  }

  /** A row of {@code SUSPEND_JOB_SQL}. */
  private record Suspension(String state, Instant waitUntil, int attempts) {}

  /** A row of {@code FIND_SLEEP_STEP_SQL}. */
  private record SleepStep(String kind, String state, Instant wakeUpAt, boolean due) {}

  /**
   * Describes a step kind for messages about a step name used for different kinds of steps.
   *
   * @param kind the stored kind
   * @return e.g. {@code "a regular step"}
   */
  public static String describeKind(String kind) {
    return switch (kind) {
      case "STEP" -> "a regular step";
      case "SLEEP" -> "a sleep";
      case "EVENT_WAIT" -> "an event wait";
      case "EVENT_PUBLISH" -> "an event publish";
      case "CHILD_JOB" -> "a child job";
      default -> "a " + kind + " step";
    };
  }

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
