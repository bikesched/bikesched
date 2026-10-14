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
import org.bikesched.RetryBackoff;
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
   * @param previous the previous (planned) run
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
   * Returns whether this is a fixed-rate recurrence: the next run is computed from the previous
   * occurrence's planned run time rather than from its scheduled time, which retries ({@link
   * JobFailure#retryAfter(Duration)}, {@link RetryBackoff}) and event waits move. Schedules that
   * only add to the previous run, like {@link #every(Duration)}, would otherwise drift; recurrences
   * that snap to the wall clock, like {@link #daily(LocalTime, ZoneId)}, are not affected and need
   * no planned time.
   *
   * <p>If {@code true}, each occurrence additionally stores its planned run time (column {@code
   * fixed_rate_at}).
   *
   * @return {@code true} to compute the next run from the planned run time; {@code false} by
   *     default ({@link #every(Duration)} returns {@code true})
   */
  default boolean isFixedRate() {
    return false;
  }

  /**
   * Runs at a fixed rate: every {@code interval} after the previous planned run (see {@link
   * #isFixedRate()}), regardless of retries or event waits of the previous occurrence.
   *
   * @param interval the time between runs, positive
   * @return the recurrence
   */
  static Recurrence every(Duration interval) {
    ValidationUtil.requirePositive(interval, "interval");
    Recurrence recurrence = of("every " + interval, previous -> previous.plus(interval));
    return new Recurrence() {
      @Override
      public Instant next(Instant previous) {
        return recurrence.next(previous);
      }

      @Override
      public String description() {
        return recurrence.description();
      }

      @Override
      public boolean isFixedRate() {
        return true; // a fixed rate must not drift when an occurrence is retried or waits
      }

      @Override
      public String toString() {
        return recurrence.description();
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
    return of(
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
    return of(
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
}
