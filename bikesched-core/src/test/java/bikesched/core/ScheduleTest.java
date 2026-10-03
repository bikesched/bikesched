package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ScheduleTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void everyAddsTheInterval() {
    Schedule schedule = Schedule.every(Duration.ofMinutes(15));

    assertThat(schedule.next(Instant.parse("2026-10-03T10:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T10:15:00Z"));
    assertThat(schedule.description()).isEqualTo("every PT15M");
  }

  @Test
  void dailyRunsAtTheLocalTime() {
    Schedule schedule = Schedule.daily(LocalTime.of(3, 0), BERLIN);

    // 02:00 Berlin (summer time, UTC+2) -> 03:00 the same day
    assertThat(schedule.next(Instant.parse("2026-10-03T00:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T01:00:00Z"));
    // exactly at 03:00 -> the next day
    assertThat(schedule.next(Instant.parse("2026-10-03T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-04T01:00:00Z"));
    // across the end of daylight saving time (2026-10-25): 03:00 is UTC+1 then
    assertThat(schedule.next(Instant.parse("2026-10-24T01:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
    assertThat(schedule.description()).isEqualTo("daily at 03:00 Europe/Berlin");
  }

  @Test
  void dailyShiftsTimesInTheDaylightSavingGap() {
    Schedule schedule = Schedule.daily(LocalTime.of(2, 30), BERLIN);

    // 2026-03-29 02:30 does not exist in Berlin: runs at 03:30 (UTC+2)
    assertThat(schedule.next(Instant.parse("2026-03-28T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
  }

  @Test
  void weeklyRunsOnTheDayAtTheLocalTime() {
    Schedule schedule = Schedule.weekly(DayOfWeek.MONDAY, LocalTime.of(8, 0), BERLIN);

    // Saturday 2026-10-03 -> Monday 2026-10-05 08:00 Berlin
    assertThat(schedule.next(Instant.parse("2026-10-03T12:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-05T06:00:00Z"));
    // exactly then -> a week later
    assertThat(schedule.next(Instant.parse("2026-10-05T06:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-12T06:00:00Z"));
    assertThat(schedule.description()).isEqualTo("weekly on MONDAY at 08:00 Europe/Berlin");
  }

  @Test
  void customScheduleHasItsDescription() {
    Schedule schedule = Schedule.of("hourly", previous -> previous.plusSeconds(3600));

    assertThat(schedule.next(Instant.EPOCH)).isEqualTo(Instant.ofEpochSecond(3600));
    assertThat(schedule.description()).isEqualTo("hourly");
    assertThat(schedule).hasToString("hourly");
  }

  @Test
  void onlyFixedRateSchedulesUsePlannedTimes() {
    assertThat(Schedule.every(Duration.ofMinutes(1)).usePlannedTime()).isTrue();
    assertThat(Schedule.daily(LocalTime.NOON, BERLIN).usePlannedTime()).isFalse();
    assertThat(Schedule.weekly(DayOfWeek.MONDAY, LocalTime.NOON, BERLIN).usePlannedTime())
        .isFalse();
    assertThat(Schedule.of("custom", previous -> previous.plusSeconds(1)).usePlannedTime())
        .isFalse();
  }

  @Test
  void invalidSchedulesAreRejected() {
    assertThatThrownBy(() -> Schedule.every(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("interval must be positive: PT0S");
    assertThatThrownBy(() -> Schedule.of(" ", previous -> previous))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("description must not be blank");
  }

  @Test
  void nextRunSkipsRunsThatAreAlreadyDue() {
    var job = recurring(Schedule.every(Duration.ofMinutes(15)));
    Instant previous = Instant.parse("2026-10-03T10:00:00Z");

    assertThat(job.nextRun(previous, Instant.parse("2026-10-03T10:05:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T10:15:00Z"));
    assertThat(job.nextRun(previous, Instant.parse("2026-10-03T12:20:00Z")))
        .as("missed runs are skipped, the rhythm is kept")
        .isEqualTo(Instant.parse("2026-10-03T12:30:00Z"));
  }

  @Test
  void nextRunRejectsSchedulesThatDoNotMoveForward() {
    var job = recurring(Schedule.of("broken", previous -> previous));

    assertThatThrownBy(() -> job.nextRun(Instant.EPOCH, Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Schedule 'broken' of recurring job report returned")
        .hasMessageEndingWith("it must return a later instant");
  }

  private static RecurringJob recurring(Schedule schedule) {
    return new RecurringJob("report", schedule, null, 0, 3, null);
  }
}
