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
 * The {@code bikesched_steps} table: durable steps of jobs and sleeps, and suspending a job at a
 * waiting step (event steps are handled by {@link EventStore}). Changes are recorded in the {@link
 * HistoryStore job history}, if enabled. Thread-safe; every call borrows its own connection from
 * the {@link DatabaseAccess}.
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
            select state, result::text, attempts from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a step that has not succeeded yet; returns its attempt number. Returns no
  // row if the step already succeeded (e.g. concurrently), was compensated since, or the job is no
  // longer running on this node.
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, locked_by, started_at)
            select ?, ?, 'RUNNING', 1, ?, now()
             where %s
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1,
                   locked_by = excluded.locked_by, started_at = now(), finished_at = null
             where bikesched_steps.state not in ('SUCCEEDED', 'COMPENSATED')
            returning attempts"""
          .formatted(JOB_LOCKED);

  private static final String COMPLETE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, compensable = ?, locked_by = null, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  private static final String COMPENSATE_STEP_SQL =
      """
            update bikesched_steps set state = 'COMPENSATED', last_error = null
             where job_id = ? and name = ? and state = 'SUCCEEDED' and %s"""
          .formatted(JOB_LOCKED);

  // The step stays SUCCEEDED: its compensation runs again in the job's next compensation attempt.
  private static final String FAIL_COMPENSATION_SQL =
      """
            update bikesched_steps set last_error = ?
             where job_id = ? and name = ? and state = 'SUCCEEDED' and %s"""
          .formatted(JOB_LOCKED);

  private static final String FAIL_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', last_error = ?, locked_by = null, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING' and locked_by = ? and %s"""
          .formatted(JOB_LOCKED);

  private static final String IS_JOB_LOCKED_SQL = "select " + JOB_LOCKED;

  // Ends a RUNNING attempt whose handler suspended at a waiting step (an event wait or a sleep):
  // WAITING until the event arrives or wait_until (scheduled_at; 'infinity' for an event wait
  // without deadline) has passed. If the step is no longer WAITING (the event was published since),
  // the job is scheduled right away, as is a job whose cancellation was requested (to cancel it).
  // Suspending does not use up an attempt.
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

  // A step of a job, for a sleep: a sleep has wait_until set and no event.
  private static final String FIND_SLEEP_STEP_SQL =
      """
            select state, event_name is not null, wait_until, wait_until <= now()
              from bikesched_steps where job_id = ? and name = ? for update""";

  // Starts a sleep: WAITING until wait_until, or SUCCEEDED right away if that has passed.
  private static final String START_SLEEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, wait_until, finished_at)
            select ?, ?, case when t.at <= now() then 'SUCCEEDED' else 'WAITING' end, 1, t.at,
                   case when t.at <= now() then now() end
              from (select coalesce(?::timestamptz, now() + ? * interval '1 millisecond') as at) t
            returning state, wait_until""";

  private static final String WAKE_UP_SQL =
      """
            update bikesched_steps set state = 'SUCCEEDED', finished_at = now()
             where job_id = ? and name = ? and state = 'WAITING'""";

  /**
   * A step as stored.
   *
   * @param state its state, e.g. {@code SUCCEEDED}, {@code WAITING} or {@code TIMED_OUT}
   * @param result its stored result (an event step: the result its job mapped the event to) as JSON
   *     text
   * @param attempts its number of attempts
   */
  public record StoredStep(String state, String result, int attempts) {}

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
                row -> new StoredStep(row.string(1), row.string(2), row.integer(3)),
                jobId,
                name),
        e ->
            new BikeschedException(
                "Reading step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * A step that completed in an earlier attempt.
   *
   * @param result its stored result as JSON text, may be {@code null}
   * @param compensated whether its compensation ran already
   * @param attempts the number of attempts it took
   */
  public record CompletedStep(String result, boolean compensated, int attempts) {}

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
                      Optional.of(new CompletedStep(step.result(), false, step.attempts()));
                  case "COMPENSATED" ->
                      Optional.of(new CompletedStep(step.result(), true, step.attempts()));
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
          if (db.update(FAIL_COMPENSATION_SQL, message, jobId, name, jobId, lock.token()) > 0) {
            history.record(
                db,
                Change.step(
                    jobId, name, "SUCCEEDED", lock.nodeId(), "compensation failed: " + error));
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
   * @throws IllegalStateException if the step name belongs to a regular step or an event wait
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public Sleep sleep(UUID jobId, JobLock lock, String name, Duration duration, Instant until) {
    return access.inTransaction(
        db -> {
          requireJobLock(db, jobId, lock);
          Optional<SleepStep> step =
              db.one(
                  FIND_SLEEP_STEP_SQL,
                  row -> new SleepStep(row.string(1), row.bool(2), row.instant(3), row.bool(4)),
                  jobId,
                  name);
          if (step.isPresent()) {
            SleepStep found = step.get();
            if (found.event() || found.wakeUpAt() == null) {
              throw new IllegalStateException(
                  "Step '"
                      + name
                      + "' of job "
                      + jobId
                      + (found.event() ? " waits for an event" : " is a regular step")
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
        db -> suspend(db, history, jobId, lock, stepName),
        e -> new BikeschedException("Suspending bikesched job " + jobId + " failed", e));
  }

  /**
   * Suspends the job at a waiting step in the caller's transaction, see {@code SUSPEND_JOB_SQL}.
   */
  static Optional<SuspendedJob> suspend(
      Sql db, HistoryStore history, UUID jobId, JobLock lock, String stepName) throws SQLException {
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
            waiting
                ? "suspended at step '" + stepName + "'"
                : "suspended at step '" + stepName + "', due again right away"));
    return Optional.of(new SuspendedJob(waiting, suspension.waitUntil()));
  }

  /** A row of {@code SUSPEND_JOB_SQL}. */
  private record Suspension(String state, Instant waitUntil, int attempts) {}

  /** A row of {@code FIND_SLEEP_STEP_SQL}. */
  private record SleepStep(String state, boolean event, Instant wakeUpAt, boolean due) {}

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
