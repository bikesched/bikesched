package org.bikesched.internal.database;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * The {@code bikesched_steps} table: durable steps of jobs (event steps are handled by {@link
 * EventStore}). Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 */
public final class StepStore {

  private final DatabaseAccess access;

  StepStore(DatabaseAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  private static final String FIND_STEP_SQL =
      """
            select state, result::text, attempts from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a step that has not succeeded yet; returns its attempt number.
  // Returns no row if the step already succeeded (e.g. concurrently), or was compensated since.
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, started_at)
            values (?, ?, 'RUNNING', 1, now())
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1, started_at = now(), finished_at = null
             where bikesched_steps.state not in ('SUCCEEDED', 'COMPENSATED')
            returning attempts""";

  private static final String COMPLETE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, compensable = ?, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  private static final String COMPENSATE_STEP_SQL =
      """
            update bikesched_steps set state = 'COMPENSATED', last_error = null
             where job_id = ? and name = ? and state = 'SUCCEEDED'""";

  // The step stays SUCCEEDED: its compensation runs again in the job's next compensation attempt.
  private static final String FAIL_COMPENSATION_SQL =
      """
            update bikesched_steps set last_error = ?
             where job_id = ? and name = ? and state = 'SUCCEEDED'""";

  private static final String FAIL_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', last_error = ?, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  /**
   * A step as stored.
   *
   * @param state its state, e.g. {@code SUCCEEDED}, {@code WAITING} or {@code TIMED_OUT}
   * @param result its stored result (an event step: the received event's payload) as JSON text
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
   * Starts an attempt of a step: creates it as {@code RUNNING} or, if an earlier attempt did not
   * succeed, sets it back to {@code RUNNING} and counts the attempt.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the attempt number of the step, or empty if the step has already succeeded
   */
  public Optional<Integer> startStep(UUID jobId, String name) {
    return access.withConnection(
        db -> db.one(START_STEP_SQL, row -> row.integer(1), jobId, name),
        e ->
            new BikeschedException(
                "Starting step '" + name + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   */
  public void completeStep(UUID jobId, String name, String result) {
    completeStep(jobId, name, result, false);
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   * @param compensable whether the step has a compensation: a cancelled or permanently failed job
   *     with such a step is compensated before it ends
   */
  public void completeStep(UUID jobId, String name, String result, boolean compensable) {
    access.withConnection(
        db -> db.update(COMPLETE_STEP_SQL, result, compensable, jobId, name),
        e ->
            new BikeschedException(
                "Storing the result of step '" + name + "' of bikesched job " + jobId + " failed",
                e));
  }

  /**
   * Marks a succeeded step {@code COMPENSATED} once its compensation ran.
   *
   * @param jobId the job id
   * @param name the step name
   */
  public void compensateStep(UUID jobId, String name) {
    access.withConnection(
        db -> db.update(COMPENSATE_STEP_SQL, jobId, name),
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
   * @param name the step name
   * @param error description of the failure, stored as the step's {@code last_error}
   */
  public void failCompensation(UUID jobId, String name, String error) {
    access.withConnection(
        db -> db.update(FAIL_COMPENSATION_SQL, error, jobId, name),
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
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   */
  public void failStep(UUID jobId, String name, String error) {
    access.withConnection(
        db -> db.update(FAIL_STEP_SQL, error, jobId, name),
        e ->
            new BikeschedException(
                "Recording the failure of step '"
                    + name
                    + "' of bikesched job "
                    + jobId
                    + " failed",
                e));
  }
}
