package bikesched.core.job;

import bikesched.core.BikeschedException;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds and schedules a job of one type, obtained via {@link
 * bikesched.core.Bikesched#newJob(String)}:
 *
 * <pre>{@code
 * UUID id = bikesched.newJob("send-mail")
 *         .payload("{\"to\": \"alice@example.com\"}")
 *         .handler(job -> mailer.send(job.payload()))   // optional if already registered
 *         .priority(10)
 *         .schedule();                                   // or schedule(instant) / schedule(delay)
 * }</pre>
 *
 * <p>{@link #connection(Connection)} continues with a {@link TransactionalJobBuilder} storing the
 * job in the caller's transaction, {@link #recurring(Schedule)} with a {@link RecurringJobBuilder}
 * for a recurring job; both keep the settings made so far.
 *
 * <p>Every {@code schedule} call stores a new job, so a builder can be reused to schedule several
 * jobs with the same settings. Not thread-safe.
 */
public interface JobBuilder extends JobSettings<JobBuilder> {

  /**
   * Continues with storing the job using the given connection, as part of the caller's transaction
   * (default: bikesched's own data source, committed immediately). The job is then created
   * atomically with the caller's other changes: it becomes visible to the pollers only once the
   * caller commits, and disappears if the caller rolls back.
   *
   * <p>bikesched only executes the insert on the connection: it does not commit, roll back, close
   * it or change its auto-commit mode. The connection must point to the database (and schema)
   * bikesched uses. If scheduling fails with a {@link BikeschedException}, PostgreSQL aborts the
   * caller's transaction, which then has to be rolled back.
   *
   * <pre>{@code
   * try (Connection connection = dataSource.getConnection()) {
   *     connection.setAutoCommit(false);
   *     insertOrder(connection, order);
   *     bikesched.newJob("send-confirmation").payload(json).connection(connection).schedule();
   *     connection.commit();   // order and job are stored together, or not at all
   * }
   * }</pre>
   *
   * <p>Recurring jobs cannot be stored in the caller's transaction: their registration also lives
   * in the node's memory, which the transaction cannot roll back.
   *
   * @param connection the caller's connection
   * @return a builder with the settings made so far, storing the job on the connection
   */
  TransactionalJobBuilder connection(Connection connection);

  /**
   * Continues with a recurring job running according to the schedule, identified by its job type;
   * see {@link RecurringJobBuilder}:
   *
   * <pre>{@code
   * bikesched.newJob("cleanup")
   *         .recurring(Schedule.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")))
   *         .handler(job -> cleanup())
   *         .schedule();
   * }</pre>
   *
   * @param schedule when the job runs
   * @return a builder with the settings made so far, registering a recurring job
   */
  RecurringJobBuilder recurring(Schedule schedule);

  /**
   * Schedules the job to run as soon as possible.
   *
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed
   */
  UUID schedule();

  /**
   * Schedules the job to run at or after the given time.
   *
   * @param at the earliest time the job may run
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed
   */
  UUID schedule(Instant at);

  /**
   * Schedules the job to run once the given delay has passed.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed
   */
  UUID schedule(Duration delay);
}
