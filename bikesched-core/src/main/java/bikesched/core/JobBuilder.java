package bikesched.core;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds and schedules a job of one type, obtained via {@link Bikesched#newJob(String)}:
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
public final class JobBuilder extends AbstractJobBuilder<JobBuilder> {

  JobBuilder(String type, JsonMapper jsonMapper, Scheduler scheduler) {
    super(type, jsonMapper, scheduler);
  }

  @Override
  JobBuilder self() {
    return this;
  }

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
  public TransactionalJobBuilder connection(Connection connection) {
    return new TransactionalJobBuilder(this, Objects.requireNonNull(connection, "connection"));
  }

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
  public RecurringJobBuilder recurring(Schedule schedule) {
    return new RecurringJobBuilder(this, Objects.requireNonNull(schedule, "schedule"));
  }

  /**
   * Schedules the job to run as soon as possible.
   *
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule() {
    return schedule(Instant.now());
  }

  /**
   * Schedules the job to run at or after the given time.
   *
   * @param at the earliest time the job may run
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule(Instant at) {
    Objects.requireNonNull(at, "at");
    return scheduler.schedule(newJob(at), handler, null);
  }

  /**
   * Schedules the job to run once the given delay has passed.
   *
   * @param delay how long to wait before the job may run, not negative
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link Bikesched} is closed
   */
  public UUID schedule(Duration delay) {
    return schedule(after(delay));
  }
}
