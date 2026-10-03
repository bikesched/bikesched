package bikesched.core.internal;

import bikesched.core.BikeschedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code bikesched_steps} table: durable steps of jobs (event steps are handled by {@link
 * EventStore}). Thread-safe; every call borrows its own connection from the {@link Database}.
 */
final class StepStore {

  private final Database database;

  StepStore(Database database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  private static final String FIND_STEP_SQL =
      """
            select state, result::text from bikesched_steps where job_id = ? and name = ?""";

  // Starts an attempt of a step that has not succeeded yet; returns its attempt number.
  // Returns no row if the step already succeeded (e.g. concurrently).
  private static final String START_STEP_SQL =
      """
            insert into bikesched_steps (job_id, name, state, attempts, started_at)
            values (?, ?, 'RUNNING', 1, now())
            on conflict (job_id, name) do update
               set state = 'RUNNING', attempts = bikesched_steps.attempts + 1, started_at = now(), finished_at = null
             where bikesched_steps.state <> 'SUCCEEDED'
            returning attempts""";

  private static final String COMPLETE_STEP_SQL =
      """
            update bikesched_steps
               set state = 'SUCCEEDED', result = ?::jsonb, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  private static final String FAIL_STEP_SQL =
      """
            update bikesched_steps
               set state = 'FAILED', last_error = ?, finished_at = now()
             where job_id = ? and name = ? and state = 'RUNNING'""";

  /**
   * A step that completed in an earlier attempt.
   *
   * @param result its stored result as JSON text, may be {@code null}
   */
  record CompletedStep(String result) {}

  /**
   * Looks up a step that succeeded in an earlier attempt of the job.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the completed step with its result, or empty if the step has not succeeded yet
   */
  Optional<CompletedStep> findCompletedStep(UUID jobId, String name) {
    try (Connection connection = database.connection();
        PreparedStatement find = connection.prepareStatement(FIND_STEP_SQL)) {
      find.setObject(1, jobId);
      find.setString(2, name);
      try (ResultSet rs = find.executeQuery()) {
        return rs.next() && "SUCCEEDED".equals(rs.getString(1))
            ? Optional.of(new CompletedStep(rs.getString(2)))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new BikeschedException(
          "Reading step '" + name + "' of bikesched job " + jobId + " failed", e);
    }
  }

  /**
   * Starts an attempt of a step: creates it as {@code RUNNING} or, if an earlier attempt did not
   * succeed, sets it back to {@code RUNNING} and counts the attempt.
   *
   * @param jobId the job id
   * @param name the step name
   * @return the attempt number of the step, or empty if the step has already succeeded
   */
  Optional<Integer> startStep(UUID jobId, String name) {
    try (Connection connection = database.connection();
        PreparedStatement start = connection.prepareStatement(START_STEP_SQL)) {
      start.setObject(1, jobId);
      start.setString(2, name);
      Integer attempts = null;
      try (ResultSet rs = start.executeQuery()) {
        if (rs.next()) {
          attempts = rs.getInt(1);
        }
      }
      Database.commitIfNeeded(connection);
      return Optional.ofNullable(attempts);
    } catch (SQLException e) {
      throw new BikeschedException(
          "Starting step '" + name + "' of bikesched job " + jobId + " failed", e);
    }
  }

  /**
   * Marks a running step {@code SUCCEEDED} and stores its result.
   *
   * @param jobId the job id
   * @param name the step name
   * @param result the result as JSON text, may be {@code null}
   */
  void completeStep(UUID jobId, String name, String result) {
    database.update(
        COMPLETE_STEP_SQL,
        "Storing the result of step '" + name + "' of bikesched job " + jobId,
        result,
        jobId,
        name);
  }

  /**
   * Marks a running step {@code FAILED}.
   *
   * @param jobId the job id
   * @param name the step name
   * @param error description of the failure, stored as {@code last_error}
   */
  void failStep(UUID jobId, String name, String error) {
    database.update(
        FAIL_STEP_SQL,
        "Recording the failure of step '" + name + "' of bikesched job " + jobId,
        error,
        jobId,
        name);
  }
}
