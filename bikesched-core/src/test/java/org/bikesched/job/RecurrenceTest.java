// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RecurrenceTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void everyAddsTheInterval() {
    Recurrence recurrence = Recurrence.every(Duration.ofMinutes(15));

    assertThat(recurrence.next(Instant.parse("2026-10-03T10:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T10:15:00Z"));
    assertThat(recurrence.description()).isEqualTo("every PT15M");
  }

  @Test
  void dailyRunsAtTheLocalTime() {
    Recurrence recurrence = Recurrence.daily(LocalTime.of(3, 0), BERLIN);

    // 02:00 Berlin (summer time, UTC+2) -> 03:00 the same day
    assertThat(recurrence.next(Instant.parse("2026-10-03T00:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T01:00:00Z"));
    // exactly at 03:00 -> the next day
    assertThat(recurrence.next(Instant.parse("2026-10-03T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-04T01:00:00Z"));
    // across the end of daylight saving time (2026-10-25): 03:00 is UTC+1 then
    assertThat(recurrence.next(Instant.parse("2026-10-24T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
    assertThat(recurrence.description()).isEqualTo("daily at 03:00 Europe/Berlin");
  }

  @Test
  void dailyShiftsTimesInTheDaylightSavingGap() {
    Recurrence recurrence = Recurrence.daily(LocalTime.of(2, 30), BERLIN);

    // 2026-03-29 02:30 does not exist in Berlin: runs at 03:30 (UTC+2)
    assertThat(recurrence.next(Instant.parse("2026-03-28T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
  }

  @Test
  void weeklyRunsOnTheDayAtTheLocalTime() {
    Recurrence recurrence = Recurrence.weekly(DayOfWeek.MONDAY, LocalTime.of(8, 0), BERLIN);

    // Saturday 2026-10-03 -> Monday 2026-10-05 08:00 Berlin
    assertThat(recurrence.next(Instant.parse("2026-10-03T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-05T06:00:00Z"));
    // exactly then -> a week later
    assertThat(recurrence.next(Instant.parse("2026-10-05T06:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-12T06:00:00Z"));
    assertThat(recurrence.description()).isEqualTo("weekly on MONDAY at 08:00 Europe/Berlin");
  }

  @Test
  void customScheduleHasItsDescription() {
    Recurrence recurrence = Recurrence.of("hourly", previous -> previous.plusSeconds(3600));

    assertThat(recurrence.next(Instant.EPOCH)).isEqualTo(Instant.ofEpochSecond(3600));
    assertThat(recurrence.description()).isEqualTo("hourly");
    assertThat(recurrence).hasToString("hourly");
  }

  @Test
  void onlyFixedRateSchedulesUsePlannedTimes() {
    assertThat(Recurrence.every(Duration.ofMinutes(1)).isFixedRate()).isTrue();
    assertThat(Recurrence.daily(LocalTime.NOON, BERLIN).isFixedRate()).isFalse();
    assertThat(Recurrence.weekly(DayOfWeek.MONDAY, LocalTime.NOON, BERLIN).isFixedRate()).isFalse();
    assertThat(Recurrence.of("custom", previous -> previous.plusSeconds(1)).isFixedRate())
        .isFalse();
  }

  @Test
  void invalidSchedulesAreRejected() {
    assertThatThrownBy(() -> Recurrence.every(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("interval must be positive: PT0S");
    assertThatThrownBy(() -> Recurrence.of(" ", previous -> previous))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("recurrence description must not be blank");
  }
}
