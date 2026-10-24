// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Recurrence expressions: {@link JobBuilder#schedule(String)} and {@link RecurrenceParser}. */
class RecurrenceParserTest extends AbstractBikeschedTest {

  /** "daily" as a recurrence of one run per day after the previous one. */
  private static final RecurrenceParser DAILY =
      new RecurrenceParser() {
        @Override
        public boolean supports(String expression) {
          return expression.equals("daily");
        }

        @Override
        public Recurrence parse(String expression) {
          return Recurrence.of("custom daily", previous -> previous.plus(Duration.ofDays(1)));
        }

        @Override
        public String toString() {
          return "DAILY";
        }
      };

  /** Claims every expression, like a too broad parser. */
  private static final RecurrenceParser EVERYTHING =
      new RecurrenceParser() {
        @Override
        public boolean supports(String expression) {
          return true;
        }

        @Override
        public Recurrence parse(String expression) {
          return Recurrence.every(Duration.ofHours(1));
        }

        @Override
        public String toString() {
          return "EVERYTHING";
        }
      };

  @AfterEach
  void deleteOccurrences() throws Exception {
    execute("delete from bikesched_jobs where type like 'parser-%'");
  }

  @Test
  void isoDurationParsesDurationsAsFixedRate() {
    RecurrenceParser parser = RecurrenceParser.isoDuration();

    assertThat(parser.supports("PT5M")).isTrue();
    assertThat(parser.supports("0 3 * * *")).isFalse();
    Recurrence recurrence = parser.parse("PT5M");
    assertThat(recurrence.description()).isEqualTo("every PT5M");
    assertThat(recurrence.isFixedDelay()).isFalse();
    assertThat(recurrence.next(Instant.EPOCH)).isEqualTo(Instant.EPOCH.plusSeconds(300));
    assertThatThrownBy(() -> parser.parse("P1Y"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid ISO-8601 duration 'P1Y': ");
  }

  @Test
  void nodesParseIsoDurationsByDefault() throws Exception {
    try (Bikesched bikesched = testNode("node-parser-default").build()) {
      UUID occurrence = bikesched.newJob("parser-default").handler(job -> {}).schedule("PT1H");

      assertThat(count("id = '" + occurrence + "' and recurrence = 'every PT1H'")).isEqualTo(1);
    }
  }

  @Test
  void configuredParsersReplaceTheDefault() throws Exception {
    try (Bikesched bikesched = testNode("node-parser-custom").recurrenceParsers(DAILY).build()) {
      UUID occurrence = bikesched.newJob("parser-custom").handler(job -> {}).schedule("daily");

      assertThat(count("id = '" + occurrence + "' and recurrence = 'custom daily'")).isEqualTo(1);
      assertThatThrownBy(() -> bikesched.newJob("parser-iso").handler(job -> {}).schedule("PT1H"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("No RecurrenceParser supports recurrence 'PT1H'");
    }
  }

  @Test
  void anExpressionSupportedByTwoParsersIsRejected() {
    try (Bikesched bikesched =
        testNode("node-parser-ambiguous")
            .recurrenceParsers(RecurrenceParser.isoDuration(), EVERYTHING)
            .build()) {
      assertThatThrownBy(
              () -> bikesched.newJob("parser-ambiguous").handler(job -> {}).schedule("PT1H"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("Multiple RecurrenceParsers support recurrence 'PT1H': ")
          .hasMessageEndingWith("EVERYTHING]");
    }
  }
}
