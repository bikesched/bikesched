// SPDX-License-Identifier: Apache-2.0
package org.bikesched.cron;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Objects;
import org.bikesched.job.Recurrence;

/**
 * A {@link Recurrence} from a cron expression, evaluated in a time zone:
 *
 * <pre>{@code
 * bikesched.newJob("report")
 *     .handler(job -> sendReport())
 *     .schedule(CronRecurrence.of("0 3 * * MON-FRI", ZoneId.of("Europe/Berlin")));
 * }</pre>
 *
 * <p>The expression is parsed with <a href="https://github.com/jmrozanec/cron-utils">cron-utils</a>
 * in one of its dialects ({@link CronType}): {@link CronType#UNIX} (five fields, minute precision)
 * by default, or e.g. {@link CronType#QUARTZ} and {@link CronType#SPRING53} with a seconds field.
 *
 * <p>The {@link #description() description} is the dialect, the normalized expression and the zone,
 * e.g. {@code cron unix 0 3 * * 1-5 Europe/Berlin}, so changing any of them moves the pending
 * occurrence. A cron recurrence snaps to the wall clock: its next run is the first match after the
 * previous planned run, and runs in the past are skipped.
 *
 * <p>Daylight saving time: a local time that does not exist on a day (the gap when clocks move
 * forward) is skipped on that day; a local time that occurs twice (when clocks move back) runs once
 * if the expression names the hour, and in both passes if the hour is a wildcard or a range
 * covering it.
 */
public final class CronRecurrence implements Recurrence {

  private final Recurrence recurrence;

  private CronRecurrence(Recurrence recurrence) {
    this.recurrence = recurrence;
  }

  /**
   * Creates a recurrence from a Unix cron expression (minute, hour, day of month, month, day of
   * week), e.g. {@code 0 3 * * MON-FRI}.
   *
   * @param expression the cron expression
   * @param zone the time zone the expression is evaluated in
   * @return the recurrence
   * @throws IllegalArgumentException if the expression is invalid or has no future execution
   */
  public static CronRecurrence of(String expression, ZoneId zone) {
    return of(expression, CronType.UNIX, zone);
  }

  /**
   * Creates a recurrence from a cron expression of the given dialect, e.g. {@code 0 0 3 ? *
   * MON-FRI} for {@link CronType#QUARTZ}.
   *
   * @param expression the cron expression
   * @param type the cron dialect
   * @param zone the time zone the expression is evaluated in
   * @return the recurrence
   * @throws IllegalArgumentException if the expression is invalid or has no future execution
   */
  public static CronRecurrence of(String expression, CronType type, ZoneId zone) {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(zone, "zone");
    String dialect = type.name().toLowerCase(Locale.ROOT);
    Cron cron;
    try {
      cron = parser(type).parse(expression).validate();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid " + dialect + " cron expression '" + expression + "': " + e.getMessage(), e);
    }
    ExecutionTime executionTime = ExecutionTime.forCron(cron);
    String description = "cron " + dialect + " " + cron.asString() + " " + zone;
    if (executionTime.nextExecution(ZonedDateTime.now(zone)).isEmpty()) {
      throw new IllegalArgumentException(
          dialect + " cron expression '" + expression + "' has no future execution");
    }
    return new CronRecurrence(
        Recurrence.of(
            description,
            previous ->
                executionTime
                    .nextExecution(previous.atZone(zone))
                    .map(ZonedDateTime::toInstant)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "'" + description + "' has no execution after " + previous))));
  }

  /**
   * Returns whether the expression is valid in the given dialect, without checking for a future
   * execution.
   *
   * @param expression the cron expression
   * @param type the cron dialect
   * @return whether cron-utils can parse it
   */
  static boolean isValid(String expression, CronType type) {
    try {
      parser(type).parse(expression).validate();
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static CronParser parser(CronType type) {
    return new CronParser(CronDefinitionBuilder.instanceDefinitionFor(type));
  }

  /**
   * Returns the next execution of the expression after {@code previous}.
   *
   * @param previous the previous (planned) run
   * @return the next execution, after {@code previous}
   * @throws IllegalStateException if the expression has no execution after {@code previous}
   */
  @Override
  public Instant next(Instant previous) {
    return recurrence.next(previous);
  }

  /**
   * Returns the dialect, the normalized expression and the zone, e.g. {@code cron unix 0 3 * * 1-5
   * Europe/Berlin}.
   *
   * @return the description
   */
  @Override
  public String description() {
    return recurrence.description();
  }

  /**
   * Returns {@code true}: a cron recurrence snaps to the wall clock.
   *
   * @return {@code true}
   */
  @Override
  public boolean isWallClock() {
    return true;
  }

  @Override
  public String toString() {
    return recurrence.description();
  }
}
