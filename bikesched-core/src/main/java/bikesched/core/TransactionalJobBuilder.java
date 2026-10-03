package bikesched.core;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds a job stored in the caller's transaction, obtained via {@link
 * JobBuilder#connection(Connection)}: the job becomes visible to the pollers only once the caller
 * commits, and disappears if the caller rolls back. bikesched does not commit, roll back or close
 * the connection, nor change its auto-commit mode.
 *
 * <p>Every {@code schedule} call stores a new job. Not thread-safe.
 */
public final class TransactionalJobBuilder extends AbstractJobBuilder<TransactionalJobBuilder> {

  private final Connection connection;

  TransactionalJobBuilder(JobBuilder settings, Connection connection) {
    super(settings);
    this.connection = connection;
  }

  @Override
  TransactionalJobBuilder self() {
    return this;
  }

  /**
   * Schedules the job to run as soon as possible once the caller's transaction commits.
   *
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule() {
    return schedule(Instant.now());
  }

  /**
   * Schedules the job to run at or after the given time, once the caller's transaction commits.
   *
   * @param at the earliest time the job may run
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, connection);
  }

  /**
   * Schedules the job to run once the given delay has passed, once the caller's transaction
   * commits.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON; PostgreSQL then aborts the caller's transaction, which has to be rolled back
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
