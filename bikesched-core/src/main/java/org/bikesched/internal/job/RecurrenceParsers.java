// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.util.List;
import java.util.Objects;
import org.bikesched.job.JobBuilder;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;

/** The recurrence parsers of a node, for {@link JobBuilder#schedule(String)}. */
public final class RecurrenceParsers {

  private final List<RecurrenceParser> parsers;

  /**
   * @param parsers the node's parsers
   */
  public RecurrenceParsers(List<RecurrenceParser> parsers) {
    this.parsers = List.copyOf(parsers);
  }

  /**
   * Parses the expression with the one parser supporting it.
   *
   * @throws IllegalArgumentException if no parser or more than one supports it, or it is invalid
   */
  Recurrence parse(String expression) {
    Objects.requireNonNull(expression, "recurrence");
    List<RecurrenceParser> matching =
        parsers.stream().filter(parser -> parser.supports(expression)).toList();
    if (matching.isEmpty()) {
      throw new IllegalArgumentException(
          "No RecurrenceParser supports recurrence '" + expression + "'");
    }
    if (matching.size() > 1) {
      throw new IllegalArgumentException(
          "Multiple RecurrenceParsers support recurrence '" + expression + "': " + matching);
    }
    return Objects.requireNonNull(
        matching.getFirst().parse(expression),
        () -> matching.getFirst() + " returned no recurrence for '" + expression + "'");
  }
}
