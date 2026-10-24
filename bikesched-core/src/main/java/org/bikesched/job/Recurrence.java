// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.bikesched.internal.util.ValidationUtil;

/**
 * When a recurring job runs, passed to {@link JobBuilder#schedule(Recurrence)}: computes the next
 * run from the previous one.
 *
 * <p>Every recurrence has a {@link #description() description}, stored with the pending occurrence.
 * When a node registers the recurring job with a recurrence of a different description (e.g. after
 * a deploy), the pending occurrence is moved to the new recurrence. The built-in recurrences
 * describe themselves; custom ones are created with {@link #of(String, UnaryOperator)} and must
 * change their description whenever their logic changes.
 */
public interface Recurrence {

  /**
   * Returns the next run after the given one.
   *
   * @param previous the previous occurrence's planned run, which retries ({@link
   *     JobFailure#retryAfter(Duration)}, {@code RetryBackoff}) and event waits do not move; for a
   *     {@linkplain #isFixedDelay() fixed delay} the time the previous occurrence ended
   * @return the next run, after {@code previous}
   */
  Instant next(Instant previous);

  /**
   * Returns a stable description of this recurrence, e.g. {@code daily at 03:00 Europe/Berlin}.
   *
   * @return the description
   */
  String description();

  /**
   * Returns whether this is a fixed-delay recurrence: the next run is computed from the time the
   * previous occurrence ended (succeeded, failed or was cancelled) instead of its planned run, so
   * every run is followed by the same gap, however long it took. An occurrence cancelled before its
   * planned run counts from its planned run, so cancelling the pending occurrence skips one run.
   *
   * @return {@code true} to compute the next run from the end of the previous occurrence; {@code
   *     false} by default ({@link #everyAfterEnd(Duration)} returns {@code true})
   */
  default boolean isFixedDelay() {
    return false;
  }

  /**
   * Returns whether this recurrence snaps to the wall clock: {@link #next(Instant)} returns the
   * first match after any given instant (a time of day, a cron expression), so the run after a
   * later instant is the same whichever earlier run it is computed from. A node then computes the
   * next run after many missed ones (e.g. after a long retry or event wait) with one call from the
   * current time instead of walking through each missed run. Recurrences that add to the previous
   * run, like {@link #every(Duration)}, must return {@code false} to keep their rhythm.
   *
   * @return {@code true} for a wall-clock recurrence; {@code false} by default ({@link
   *     #daily(LocalTime, ZoneId)} and {@link #weekly(DayOfWeek, LocalTime, ZoneId)} return {@code
   *     true})
   */
  default boolean isWallClock() {
    return false;
  }

  /**
   * Runs at a fixed rate: every {@code interval} after the previous planned run, regardless of
   * retries or event waits of the previous occurrence. Runs missed because the previous occurrence
   * took longer are skipped.
   *
   * @param interval the time between runs, positive
   * @return the recurrence
   */
  static Recurrence every(Duration interval) {
    ValidationUtil.requirePositive(interval, "interval");
    return of("every " + interval, previous -> previous.plus(interval));
  }

  /**
   * Runs with a fixed delay: {@code interval} after the previous occurrence ended (succeeded,
   * failed or was cancelled), however long it ran (see {@link #isFixedDelay()}). Its description is
   * {@code every <interval> after end}, e.g. {@code every PT15M after end}.
   *
   * @param interval the time between the end of one run and the start of the next, positive
   * @return the recurrence
   */
  static Recurrence everyAfterEnd(Duration interval) {
    ValidationUtil.requirePositive(interval, "interval");
    String description = "every " + interval + " after end";
    return new Recurrence() {
      @Override
      public Instant next(Instant previous) {
        return previous.plus(interval);
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public boolean isFixedDelay() {
        return true;
      }

      @Override
      public String toString() {
        return description;
      }
    };
  }

  /**
   * Runs every day at the given local time. On days where the time does not exist (daylight saving
   * gap), it runs at the shifted time, e.g. 03:30 instead of 02:30.
   *
   * @param time the local time of day
   * @param zone the time zone of {@code time}
   * @return the recurrence
   */
  static Recurrence daily(LocalTime time, ZoneId zone) {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(zone, "zone");
    return wallClock(
        "daily at " + time + " " + zone,
        previous -> {
          ZonedDateTime start = previous.atZone(zone);
          ZonedDateTime next = ZonedDateTime.of(start.toLocalDate(), time, zone);
          return next.toInstant().isAfter(previous)
              ? next.toInstant()
              : ZonedDateTime.of(start.toLocalDate().plusDays(1), time, zone).toInstant();
        });
  }

  /**
   * Runs every week on the given day at the given local time.
   *
   * @param day the day of the week
   * @param time the local time of day
   * @param zone the time zone of {@code time}
   * @return the recurrence
   */
  static Recurrence weekly(DayOfWeek day, LocalTime time, ZoneId zone) {
    Objects.requireNonNull(day, "day");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(zone, "zone");
    return wallClock(
        "weekly on " + day + " at " + time + " " + zone,
        previous -> {
          var date = previous.atZone(zone).toLocalDate().with(TemporalAdjusters.nextOrSame(day));
          Instant next = ZonedDateTime.of(date, time, zone).toInstant();
          return next.isAfter(previous)
              ? next
              : ZonedDateTime.of(date.plusWeeks(1), time, zone).toInstant();
        });
  }

  /**
   * Creates a custom recurrence:
   *
   * <pre>{@code
   * Recurrence.of("first monday of the month at 08:00 UTC", previous -> firstMondayAfter(previous))
   * }</pre>
   *
   * @param description a stable description, not blank, at most 200 characters; change it whenever
   *     the logic changes, so nodes move the pending occurrence to the new recurrence
   * @param next computes the next run after the previous one; must return a later instant
   * @return the recurrence
   */
  static Recurrence of(String description, UnaryOperator<Instant> next) {
    ValidationUtil.requireName(description, "recurrence description");
    Objects.requireNonNull(next, "next");
    return new Recurrence() {
      @Override
      public Instant next(Instant previous) {
        return next.apply(previous);
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public String toString() {
        return description;
      }
    };
  }

  /** A recurrence created with {@link #of(String, UnaryOperator)} that snaps to the wall clock. */
  private static Recurrence wallClock(String description, UnaryOperator<Instant> next) {
    Recurrence recurrence = of(description, next);
    return new Recurrence() {
      @Override
      public Instant next(Instant previous) {
        return recurrence.next(previous);
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public boolean isWallClock() {
        return true;
      }

      @Override
      public String toString() {
        return description;
      }
    };
  }
}
