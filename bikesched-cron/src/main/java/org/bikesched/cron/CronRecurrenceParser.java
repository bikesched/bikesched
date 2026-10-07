// SPDX-License-Identifier: Apache-2.0
package org.bikesched.cron;

import com.cronutils.model.CronType;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;

/**
 * Parses cron expressions into a {@link CronRecurrence}, for {@code JobBuilder.schedule(String)}
 * and {@code @JobHandler(schedule = …)}:
 *
 * <pre>{@code
 * Bikesched.builder()
 *     .recurrenceParsers(
 *         RecurrenceParser.isoDuration(),
 *         new CronRecurrenceParser(CronType.UNIX, ZoneId.of("Europe/Berlin")))
 *     …
 * bikesched.newJob("report").handler(job -> sendReport()).schedule("0 3 * * MON-FRI");
 * }</pre>
 *
 * <p>It supports every expression that is valid in its dialect; all expressions of one parser are
 * evaluated in its time zone.
 */
public final class CronRecurrenceParser implements RecurrenceParser {

  private final CronType type;
  private final ZoneId zone;

  /** Creates a parser for Unix cron expressions, evaluated in UTC. */
  public CronRecurrenceParser() {
    this(CronType.UNIX, ZoneOffset.UTC);
  }

  /**
   * Creates a parser for cron expressions of the given dialect.
   *
   * @param type the cron dialect
   * @param zone the time zone the expressions are evaluated in
   */
  public CronRecurrenceParser(CronType type, ZoneId zone) {
    this.type = Objects.requireNonNull(type, "type");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  @Override
  public boolean supports(String expression) {
    return CronRecurrence.isValid(expression, type);
  }

  @Override
  public Recurrence parse(String expression) {
    return CronRecurrence.of(expression, type, zone);
  }

  @Override
  public String toString() {
    return "CronRecurrenceParser[" + type + ", " + zone + "]";
  }
}
