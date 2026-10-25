// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.job.Recurrence;
import org.junit.jupiter.api.Test;

class RecurringJobNextRunTest {

  @Test
  void nextRunSkipsRunsThatAreAlreadyDue() {
    var job = recurring(Recurrence.every(Duration.ofMinutes(15)));
    Instant previous = Instant.parse("2026-10-03T10:00:00Z");

    assertThat(job.nextRun(previous, Instant.parse("2026-10-03T10:05:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T10:15:00Z"));
    assertThat(job.nextRun(previous, Instant.parse("2026-10-03T12:20:00Z")))
        .as("missed runs are skipped, the rhythm is kept")
        .isEqualTo(Instant.parse("2026-10-03T12:30:00Z"));
  }

  @Test
  void nextRunAfterEndCountsFromThePlannedRun() {
    var job = recurring(Recurrence.of("hourly", previous -> previous.plusSeconds(3600)));
    Instant planned = Instant.parse("2026-10-03T10:00:00Z");

    assertThat(job.nextRunAfterEnd(planned, Instant.parse("2026-10-03T10:20:00Z")))
        .as("retries and waits that moved the run do not shift the next one")
        .isEqualTo(Instant.parse("2026-10-03T11:00:00Z"));
  }

  @Test
  void fixedDelayCountsFromTheEnd() {
    var job = recurring(Recurrence.everyAfterEnd(Duration.ofMinutes(15)));
    Instant planned = Instant.parse("2026-10-03T10:00:00Z");

    assertThat(job.nextRunAfterEnd(planned, Instant.parse("2026-10-03T10:10:00Z")))
        .isEqualTo(Instant.parse("2026-10-03T10:25:00Z"));
    assertThat(job.nextRunAfterEnd(planned, Instant.parse("2026-10-03T11:40:00Z")))
        .as("no run is skipped")
        .isEqualTo(Instant.parse("2026-10-03T11:55:00Z"));
    assertThat(job.nextRunAfterEnd(planned, Instant.parse("2026-10-03T09:55:00Z")))
        .as("cancelled before its planned run: one run is skipped")
        .isEqualTo(Instant.parse("2026-10-03T10:15:00Z"));
  }

  @Test
  void wallClockRecurrenceSkipsMissedRunsInOneStep() {
    var calls = new AtomicInteger();
    Recurrence everySecond =
        new Recurrence() {
          @Override
          public Instant next(Instant previous) {
            calls.incrementAndGet();
            return previous.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
          }

          @Override
          public String description() {
            return "every second on the second";
          }

          @Override
          public boolean isWallClock() {
            return true;
          }
        };
    var job = recurring(everySecond);
    Instant planned = Instant.parse("2026-10-03T10:00:00Z");

    assertThat(job.nextRun(planned, Instant.parse("2026-10-04T10:00:00.500Z")))
        .isEqualTo(Instant.parse("2026-10-04T10:00:01Z"));
    assertThat(calls).as("a day of missed runs is not walked through").hasValue(2);
  }

  @Test
  void nextRunRejectsSchedulesThatDoNotMoveForward() {
    var job = recurring(Recurrence.of("broken", previous -> previous));

    assertThatThrownBy(() -> job.nextRun(Instant.EPOCH, Instant.EPOCH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Recurrence 'broken' of recurring job report returned")
        .hasMessageEndingWith("it must return a later instant");
  }

  private static RecurringJob recurring(Recurrence recurrence) {
    return new RecurringJob("report", recurrence, null, 0, 3, null, null);
  }
}
