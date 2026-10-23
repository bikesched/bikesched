// SPDX-License-Identifier: Apache-2.0
package org.bikesched.cron;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cronutils.model.CronType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CronRecurrenceTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void unixExpressionRunsInTheZone() {
    CronRecurrence recurrence = CronRecurrence.of("0 3 * * MON-FRI", BERLIN);

    // Saturday 2026-10-03 -> Monday 2026-10-05 03:00 Berlin (UTC+2)
    assertThat(recurrence.next(Instant.parse("2026-10-03T00:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-05T01:00:00Z"));
    // exactly then -> the next weekday
    assertThat(recurrence.next(Instant.parse("2026-10-05T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-06T01:00:00Z"));
    assertThat(recurrence.isFixedDelay()).isFalse();
  }

  @Test
  void descriptionIsTheDialectTheNormalizedExpressionAndTheZone() {
    assertThat(CronRecurrence.of("0  3 *  * MON-FRI", BERLIN).description())
        .isEqualTo("cron unix 0 3 * * 1-5 Europe/Berlin");
    assertThat(CronRecurrence.of("0 3 * * 1-5", BERLIN))
        .hasToString("cron unix 0 3 * * 1-5 Europe/Berlin");
    assertThat(
            CronRecurrence.of("0 0 3 ? * MON-FRI", CronType.QUARTZ, ZoneOffset.UTC).description())
        .isEqualTo("cron quartz 0 0 3 ? * 2-6 Z");
  }

  @Test
  void dialectsWithSecondsRunEverySecond() {
    CronRecurrence recurrence = CronRecurrence.of("*/10 * * * * *", CronType.SPRING53, BERLIN);

    assertThat(recurrence.next(Instant.parse("2026-10-03T00:00:01Z")))
        .isEqualTo(Instant.parse("2026-10-03T00:00:10Z"));
  }

  @Test
  void localTimesInTheDaylightSavingGapAreSkipped() {
    CronRecurrence recurrence = CronRecurrence.of("30 2 * * *", BERLIN);

    // 2026-03-29 02:30 does not exist in Berlin: the next run is on 2026-03-30
    assertThat(recurrence.next(Instant.parse("2026-03-28T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-03-30T00:30:00Z"));
  }

  @Test
  void repeatedLocalTimesRunOnceForAFixedHour() {
    CronRecurrence recurrence = CronRecurrence.of("30 2 * * *", BERLIN);

    // 2026-10-25 02:30 occurs at 00:30Z (UTC+2) and 01:30Z (UTC+1): only the first runs
    assertThat(recurrence.next(Instant.parse("2026-10-24T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    assertThat(recurrence.next(Instant.parse("2026-10-25T00:30:00Z")))
        .isEqualTo(Instant.parse("2026-10-26T01:30:00Z"));
  }

  @Test
  void repeatedLocalTimesRunTwiceForAWildcardHour() {
    CronRecurrence recurrence = CronRecurrence.of("*/30 * * * *", BERLIN);

    // 02:30 (UTC+2) -> 02:00 (UTC+1) -> 02:30 (UTC+1)
    assertThat(recurrence.next(Instant.parse("2026-10-25T00:30:00Z")))
        .isEqualTo(Instant.parse("2026-10-25T01:00:00Z"));
    assertThat(recurrence.next(Instant.parse("2026-10-25T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
  }

  @Test
  void invalidExpressionsAreRejected() {
    assertThatThrownBy(() -> CronRecurrence.of("*/10 * * * * *", BERLIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid unix cron expression '*/10 * * * * *': ");
    assertThatThrownBy(() -> CronRecurrence.of("0 61 * * *", BERLIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid unix cron expression '0 61 * * *': ");
  }

  @Test
  void expressionsWithoutAFutureExecutionAreRejected() {
    assertThatThrownBy(() -> CronRecurrence.of("0 0 30 2 *", BERLIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unix cron expression '0 0 30 2 *' has no future execution");
    assertThatThrownBy(() -> CronRecurrence.of("0 0 0 1 1 ? 2020", CronType.QUARTZ, BERLIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("quartz cron expression '0 0 0 1 1 ? 2020' has no future execution");
  }

  @Test
  void anEndedExpressionFailsTheNextRun() {
    CronRecurrence recurrence = CronRecurrence.of("0 0 0 1 1 ? 2099", CronType.QUARTZ, BERLIN);

    assertThatThrownBy(() -> recurrence.next(Instant.parse("2099-06-01T00:00:00Z")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "'cron quartz 0 0 0 1 1 ? 2099 Europe/Berlin' has no execution after"
                + " 2099-06-01T00:00:00Z");
  }
}
