// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * Builds and schedules a job of one type, obtained via {@link
 * org.bikesched.Bikesched#newJob(String)} or, with a typed payload, {@link
 * org.bikesched.Bikesched#newJob(JobType)}:
 *
 * <pre>{@code
 * UUID id = bikesched.newJob("send-mail")
 *         .payload("{\"to\": \"alice@example.com\"}")
 *         .handler(job -> mailer.send(job.payload()))   // optional if already registered
 *         .priority(10)
 *         .schedule();                                   // or schedule(instant) / schedule(delay)
 *
 * bikesched.newJob("cleanup")
 *         .handler(job -> cleanup())
 *         .schedule(Recurrence.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")));
 * }</pre>
 *
 * <p>The argument of {@code schedule} decides what is created: no argument, an {@link Instant} or a
 * {@link Duration} store a one-off job; a {@link Recurrence} registers a recurring job (see {@link
 * #schedule(Recurrence)}). {@link #connection(Connection)} continues with a {@link
 * TransactionalJobBuilder} storing a one-off job in the caller's transaction; it keeps the settings
 * made so far.
 *
 * <p>Every one-off {@code schedule} call stores a new job, so a builder can be reused to schedule
 * several jobs with the same settings. Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for a job type given as a string, the payload class
 *     of a {@link JobType}
 */
public interface JobBuilder<P> extends JobHandlerSettings<JobBuilder<P>, P> {

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
   * <p>Recurring jobs cannot be stored in the caller's transaction (the returned builder has no
   * {@code schedule(Recurrence)}): their registration also lives in the node's memory, which the
   * transaction cannot roll back.
   *
   * @param connection the caller's connection
   * @return a builder with the settings made so far, storing the job on the connection
   */
  TransactionalJobBuilder<P> connection(Connection connection);

  /**
   * Schedules the job to run as soon as possible.
   *
   * @return the id of the scheduled job
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the job cannot be stored, e.g. because the payload is not valid
   *     JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
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
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
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
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Duration delay);

  /**
   * Registers a recurring job, identified by its job type, running according to the recurrence.
   * Every node registering it runs the same code, typically on startup:
   *
   * <pre>{@code
   * bikesched.newJob("cleanup")
   *         .handler(job -> cleanup())
   *         .schedule(Recurrence.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")));
   * }</pre>
   *
   * <p>Unlike the one-off {@code schedule} methods, this registration is idempotent: it creates the
   * first occurrence unless one is pending, so calling it again (on every node, on every start)
   * does not add jobs. If the pending occurrence belongs to a recurrence of another {@link
   * Recurrence#description() description} (e.g. after a deploy) and has not started yet, it is
   * moved to the new recurrence. A new first occurrence runs at the recurrence's next run from now.
   *
   * <p>Each run is an occurrence: a regular job with its own retries, timeout, steps and events;
   * the payload, priority, maximum attempts and timeout apply to every occurrence. At most one
   * occurrence is pending at a time. When an occurrence ends ({@code SUCCEEDED}, permanently {@code
   * FAILED} or {@code CANCELLED}, which cancels this occurrence only), the next one is scheduled in
   * the same transaction; runs that would already be due are skipped, not caught up on. Nodes
   * recreate a missing occurrence of the recurring jobs they registered once when they start
   * polling; {@link org.bikesched.Bikesched#removeRecurringJob(String)} stops a recurring job that
   * no node registers anymore.
   *
   * @param recurrence when the job runs
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed or the
   *     recurrence does not return a later instant
   */
  UUID schedule(Recurrence recurrence);

  /**
   * Registers a recurring job like {@link #schedule(Recurrence)}, with the recurrence parsed from
   * an expression by the node's {@link RecurrenceParser}s:
   *
   * <pre>{@code
   * bikesched.newJob("cleanup").handler(job -> cleanup()).schedule("PT1H");
   * }</pre>
   *
   * <p>By default a node parses ISO-8601 durations ({@link RecurrenceParser#isoDuration()}); other
   * formats, e.g. cron expressions from {@code bikesched-cron}, are configured with {@code
   * BikeschedBuilder.recurrenceParsers(…)}.
   *
   * @param recurrence the recurrence expression, e.g. {@code PT1H}
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if not exactly one of the node's parsers supports the
   *     expression, the expression is invalid, or no handler is set nor registered for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed or the
   *     recurrence does not return a later instant
   */
  UUID schedule(String recurrence);

  /**
   * Registers a recurring job like {@link #schedule(Recurrence)}; a new first occurrence runs at
   * the given time (also when moving the pending occurrence to a changed recurrence), the following
   * ones according to the recurrence.
   *
   * @param recurrence when the job runs
   * @param firstRun when a new first occurrence runs
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Recurrence recurrence, Instant firstRun);

  /**
   * Registers a recurring job like {@link #schedule(Recurrence)}; a new first occurrence runs once
   * the given delay has passed, the following ones according to the recurrence.
   *
   * @param recurrence when the job runs
   * @param delay how long to wait before a new first occurrence runs, not negative
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link org.bikesched.Bikesched} is closed
   */
  UUID schedule(Recurrence recurrence, Duration delay);
}
