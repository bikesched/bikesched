package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.Recurrence;

/**
 * A recurring job registered on this node (via {@link JobBuilder#schedule(Recurrence)}): its type
 * (the identity of the recurring job), its recurrence and the attributes of its occurrences.
 *
 * @param type the job type
 * @param recurrence when it runs
 * @param payload the payload of each occurrence as JSON text, may be {@code null}
 * @param priority the priority of each occurrence
 * @param maxAttempts the maximum number of attempts of each occurrence
 * @param timeout the timeout of each occurrence's attempts, {@code null} for the node default
 */
public record RecurringJob(
    String type,
    Recurrence recurrence,
    String payload,
    int priority,
    int maxAttempts,
    Duration timeout) {

  /** Upper bound of runs skipped at once, so a misbehaving recurrence cannot loop forever. */
  private static final int MAX_SKIPPED_RUNS = 100_000;

  public RecurringJob {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(recurrence, "recurrence");
    // Also for Recurrence implementations not created via Recurrence.of.
    ValidationUtil.requireName(recurrence.description(), "recurrence description");
  }

  /** An occurrence running at the given time. */
  public NewJob occurrence(Instant at) {
    return new NewJob(
        type,
        payload,
        at,
        priority,
        maxAttempts,
        timeout,
        recurrence.description(),
        recurrence.isFixedRate() ? at : null);
  }

  /**
   * The next run after an ended occurrence: computed from its planned run if the recurrence {@link
   * Recurrence#isFixedRate() is fixed-rate} and it has one, otherwise from its scheduled time
   * (which retries and event waits may have moved).
   */
  Instant nextRun(DefaultJob ended, Instant now) {
    Instant previous =
        recurrence.isFixedRate() && ended.fixedRateAt() != null
            ? ended.fixedRateAt()
            : ended.scheduledAt();
    return nextRun(previous, now);
  }

  /**
   * The next run after {@code previous} that is still in the future: runs missed while no node was
   * running (or the previous occurrence took longer) are skipped, not caught up on.
   *
   * @throws IllegalStateException if the recurrence does not return a later instant
   */
  public Instant nextRun(Instant previous, Instant now) {
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
    Instant next = recurrence.next(previous);
    if (next == null || !next.isAfter(previous)) {
      throw new IllegalStateException(
          "Recurrence '"
              + recurrence.description()
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
