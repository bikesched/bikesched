package bikesched.core.job;

import bikesched.core.BikeschedException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds and registers a recurring job, obtained via {@link JobBuilder#recurring(Schedule)}. The
 * job type identifies the recurring job; every node registering it runs the same code, typically on
 * startup:
 *
 * <pre>{@code
 * bikesched.newJob("cleanup")
 *         .recurring(Schedule.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")))
 *         .handler(job -> cleanup())
 *         .schedule();
 * }</pre>
 *
 * <p>Each run is an occurrence: a regular job with its own retries, timeout, steps and events; the
 * payload, priority, maximum attempts and timeout apply to every occurrence. At most one occurrence
 * is pending at a time. When an occurrence ends ({@code SUCCEEDED}, permanently {@code FAILED} or
 * {@code CANCELLED}, which cancels this occurrence only), the next one is scheduled in the same
 * transaction for {@code schedule.next(the ended occurrence's scheduled time)}; runs that would
 * already be due are skipped, not caught up on.
 *
 * <p>The {@code schedule} methods register the recurring job and are idempotent: they create the
 * first occurrence unless one is pending. If the pending occurrence belongs to a schedule of
 * another {@link Schedule#description() description} (e.g. after a deploy) and has not started yet,
 * it is moved to the new schedule. Nodes recreate a missing occurrence of the recurring jobs they
 * registered on every heartbeat; {@link bikesched.core.Bikesched#removeRecurringJob(String)} stops
 * a recurring job that no node registers anymore.
 *
 * <p>Recurring jobs cannot be stored in the caller's transaction (there is no {@code connection}
 * method): their registration also lives in the node's memory. Not thread-safe.
 */
public interface RecurringJobBuilder extends JobSettings<RecurringJobBuilder> {

  /**
   * Registers the recurring job; a new first occurrence runs at the schedule's next run from now.
   *
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed or the schedule
   *     does not return a later instant
   */
  UUID schedule();

  /**
   * Registers the recurring job; a new first occurrence runs at the given time (also when moving
   * the pending occurrence to a changed schedule), the following ones according to the schedule.
   *
   * @param firstRun when a new first occurrence runs
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if no handler is set nor registered for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed
   */
  UUID schedule(Instant firstRun);

  /**
   * Registers the recurring job; a new first occurrence runs once the given delay has passed.
   *
   * @param delay how long to wait before a new first occurrence runs, not negative
   * @return the id of the pending occurrence
   * @throws IllegalArgumentException if the delay is negative or no handler is set nor registered
   *     for the job type
   * @throws BikeschedException if the occurrence cannot be stored, e.g. because the payload is not
   *     valid JSON
   * @throws IllegalStateException if the {@link bikesched.core.Bikesched} is closed
   */
  UUID schedule(Duration delay);
}
