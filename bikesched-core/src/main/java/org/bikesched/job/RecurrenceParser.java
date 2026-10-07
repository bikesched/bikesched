// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Turns a recurrence expression into a {@link Recurrence}, for {@link JobBuilder#schedule(String)}
 * and the {@code schedule} of {@code @JobHandler} methods. A node has a set of parsers ({@code
 * BikeschedBuilder.recurrenceParsers(…)}, by default only {@link #isoDuration()}); an expression
 * must be supported by exactly one of them.
 */
public interface RecurrenceParser {

  /**
   * Whether this parser recognizes the expression. Only one parser of a node may recognize an
   * expression.
   *
   * @param expression the recurrence expression
   * @return whether this parser handles it
   */
  boolean supports(String expression);

  /**
   * Parses an expression this parser {@link #supports(String) supports}.
   *
   * @param expression the recurrence expression
   * @return the recurrence
   * @throws IllegalArgumentException if the expression is invalid
   */
  Recurrence parse(String expression);

  /**
   * Parses ISO-8601 durations such as {@code PT5M} into {@link Recurrence#every(Duration)}, a
   * fixed-rate recurrence. Supports every expression starting with {@code P}.
   *
   * @return the parser
   */
  static RecurrenceParser isoDuration() {
    return new RecurrenceParser() {
      @Override
      public boolean supports(String expression) {
        return expression.startsWith("P");
      }

      @Override
      public Recurrence parse(String expression) {
        Duration interval;
        try {
          interval = Duration.parse(expression);
        } catch (DateTimeParseException e) {
          throw new IllegalArgumentException(
              "Invalid ISO-8601 duration '" + expression + "': " + e.getMessage(), e);
        }
        return Recurrence.every(interval);
      }

      @Override
      public String toString() {
        return "RecurrenceParser.isoDuration()";
      }
    };
  }
}
