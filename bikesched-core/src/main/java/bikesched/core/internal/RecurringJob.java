package bikesched.core.internal;

import bikesched.core.job.JobBuilder;
import bikesched.core.job.Schedule;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A recurring job registered on this node (via {@link JobBuilder#recurring(Schedule)}): its type
 * (the identity of the recurring job), its schedule and the attributes of its occurrences.
 *
 * @param type the job type
 * @param schedule when it runs
 * @param payload the payload of each occurrence as JSON text, may be {@code null}
 * @param priority the priority of each occurrence
 * @param maxAttempts the maximum number of attempts of each occurrence
 * @param timeout the timeout of each occurrence's attempts, {@code null} for the node default
 */
record RecurringJob(
    String type,
    Schedule schedule,
    String payload,
    int priority,
    int maxAttempts,
    Duration timeout) {

  /** Upper bound of runs skipped at once, so a misbehaving schedule cannot loop forever. */
  private static final int MAX_SKIPPED_RUNS = 100_000;

  RecurringJob {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(schedule, "schedule");
  }

  /** An occurrence running at the given time. */
  NewJob occurrence(Instant at) {
    return new NewJob(
        type,
        payload,
        at,
        priority,
        maxAttempts,
        timeout,
        schedule.description(),
        schedule.isFixedRate() ? at : null);
  }

  /**
   * The next run after an ended occurrence: computed from its planned run if the schedule {@link
   * Schedule#isFixedRate() is fixed-rate} and it has one, otherwise from its scheduled time (which
   * retries and event waits may have moved).
   */
  Instant nextRun(DefaultJob ended, Instant now) {
    Instant previous =
        schedule.isFixedRate() && ended.fixedRateAt() != null
            ? ended.fixedRateAt()
            : ended.scheduledAt();
    return nextRun(previous, now);
  }

  /**
   * The next run after {@code previous} that is still in the future: runs missed while no node was
   * running (or the previous occurrence took longer) are skipped, not caught up on.
   *
   * @throws IllegalStateException if the schedule does not return a later instant
   */
  Instant nextRun(Instant previous, Instant now) {
    Instant next = next(previous);
    for (int skipped = 0; !next.isAfter(now); skipped++) {
      if (skipped == MAX_SKIPPED_RUNS) {
        return next(now);
      }
      next = next(next);
    }
    return next;
  }

  private Instant next(Instant previous) {
    Instant next = schedule.next(previous);
    if (next == null || !next.isAfter(previous)) {
      throw new IllegalStateException(
          "Schedule '"
              + schedule.description()
              + "' of recurring job "
              + type
              + " returned "
              + next
              + " as the run after "
              + previous
              + "; it must return a later instant");
    }
    return next;
  }
}
