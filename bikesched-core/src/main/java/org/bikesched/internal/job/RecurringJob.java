// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.time.Instant;
import org.bikesched.internal.database.JobOrigin;
import org.bikesched.internal.database.NewJob;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.Recurrence;
import org.jspecify.annotations.Nullable;

/**
 * A recurring job registered on this node (via {@link JobBuilder#schedule(Recurrence)}): its name
 * (the identity of the recurring job), its recurrence and the attributes of its occurrences.
 *
 * @param name the job name
 * @param recurrence when it runs
 * @param payload the payload of each occurrence as JSON text, may be {@code null}
 * @param priority the priority of each occurrence
 * @param maxAttempts the maximum number of attempts of each occurrence
 * @param timeout the timeout of each occurrence's attempts, {@code null} for the node default
 */
public record RecurringJob(
    String name,
    Recurrence recurrence,
    @Nullable String payload,
    int priority,
    int maxAttempts,
    @Nullable Duration timeout) {

  /** Upper bound of runs skipped at once, so a misbehaving recurrence cannot loop forever. */
  private static final int MAX_SKIPPED_RUNS = 100_000;

  public RecurringJob {
    // Also for Recurrence implementations not created via Recurrence.of.
    ValidationUtil.requireName(recurrence.description(), "recurrence description");
  }

  /** An occurrence running at the given time. */
  public NewJob occurrence(Instant at) {
    return new NewJob(
        name, payload, at, priority, maxAttempts, timeout, recurrence.description(), at);
  }

  /** The next run after an occurrence that ended now, see {@link #nextRunAfterEnd}. */
  Instant nextRun(DefaultJob ended, Instant now) {
    Instant planned =
        switch (ended.origin()) {
          case JobOrigin.Occurrence(String ignored, Instant plannedRunAt) -> plannedRunAt;
          case JobOrigin.OneOff ignored -> ended.scheduledAt();
          case JobOrigin.Child ignored -> ended.scheduledAt();
        };
    return nextRunAfterEnd(planned, now);
  }

  /**
   * The next run after an occurrence that ended: computed from its planned run, which retries and
   * event waits do not move, or, if the recurrence is a {@linkplain Recurrence#isFixedDelay() fixed
   * delay}, from its end. An occurrence cancelled before its planned run counts from its planned
   * run, so cancelling it skips one run instead of starting the delay again.
   *
   * @param planned the occurrence's planned run
   * @param ended when it ended (now)
   */
  Instant nextRunAfterEnd(Instant planned, Instant ended) {
    Instant previous = recurrence.isFixedDelay() && ended.isAfter(planned) ? ended : planned;
    return nextRun(previous, ended);
  }

  /**
   * The next run after {@code previous} that is still in the future: runs missed while no node was
   * running (or the previous occurrence took longer) are skipped, not caught up on. A {@linkplain
   * Recurrence#isWallClock() wall-clock} recurrence skips them in one step from {@code now}.
   *
   * @throws IllegalStateException if the recurrence does not return a later instant
   */
  public Instant nextRun(Instant previous, Instant now) {
    Instant next = next(previous);
    if (!next.isAfter(now) && recurrence.isWallClock()) {
      return next(now);
    }
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
              + name
              + " returned "
              + next
              + " as the run after "
              + previous
              + "; it must return a later instant");
    }
    return next;
  }
}
