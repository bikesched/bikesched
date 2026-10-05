package org.bikesched.internal.database;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.ValidationUtil;

/**
 * The {@code bikesched_steps} table: durable steps of jobs (event steps are handled by {@link
 * EventStore}). Changes are recorded in the {@link HistoryStore job history}, if enabled.
 * Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
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

  // Stores the result of an event step whose job mapped the received event.
  private static final String COMPLETE_EVENT_STEP_SQL =
      """
            update bikesched_steps set state = 'SUCCEEDED', result = ?::jsonb, finished_at = now()
             where job_id = ? and name = ? and state = 'RECEIVED' and %s"""
          .formatted(JOB_LOCKED);

  private static final String IS_JOB_LOCKED_SQL = "select " + JOB_LOCKED;

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
   * Marks a {@code RECEIVED} event step {@code SUCCEEDED} and stores the result its job mapped the
   * event to; the event is no longer needed for it (see {@link EventStore#deleteExpiredEvents()}).
   *
   * @param jobId the job id
   * @param lock the lock of the job's claim (node and token)
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   * @throws JobLockLostException if the job is no longer running on the node
   */
  public void completeEventStep(UUID jobId, JobLock lock, String name, String result) {
    history.change(
        db -> {
          if (db.update(COMPLETE_EVENT_STEP_SQL, result, jobId, name, jobId, lock.token()) > 0) {
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
