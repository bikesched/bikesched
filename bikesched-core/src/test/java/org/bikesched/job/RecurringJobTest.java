// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.DefaultBikesched;
import org.bikesched.internal.job.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Recurring jobs: {@link JobBuilder#schedule(Recurrence)}. */
class RecurringJobTest extends AbstractBikeschedTest {

  private static final Recurrence EVERY_200_MS = Recurrence.every(Duration.ofMillis(200));

  /**
   * Deletes the occurrences left pending: otherwise the nodes of later tests (which do not register
   * these recurring jobs) would claim and fail them.
   */
  @AfterEach
  void deleteOccurrences() throws Exception {
    execute("delete from bikesched_jobs where type like 'recurring-%'");
  }

  @Test
  void recurringJobRunsRepeatedlyWithOnePendingOccurrence() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = node("node-recurring")) {
      UUID first =
          bikesched
              .newJob("recurring-basic")
              .payload("{\"report\": 1}")
              .handler(
                  job -> {
                    assertThat(job.payload()).isEqualTo("{\"report\": 1}");
                    runs.incrementAndGet();
                  })
              .schedule(EVERY_200_MS);

      assertThat(count("id = '" + first + "' and recurrence = 'every PT0.2S'")).isEqualTo(1);
      awaitCount("type = 'recurring-basic' and state = 'SUCCEEDED'", 3);

      assertThat(count("type = 'recurring-basic' and state in ('SCHEDULED', 'RUNNING')"))
          .as("exactly one pending occurrence")
          .isEqualTo(1);
    }
    assertThat(runs).hasValueGreaterThanOrEqualTo(3);
  }

  @Test
  void registeringOnSeveralNodesCreatesOneOccurrence() throws Exception {
    try (Bikesched first = node("node-recurring-a");
        Bikesched second = node("node-recurring-b")) {
      Recurrence hourly = Recurrence.every(Duration.ofHours(1));
      UUID a = first.newJob("recurring-shared").handler(() -> {}).schedule(hourly);
      UUID b = second.newJob("recurring-shared").handler(() -> {}).schedule(hourly);

      assertThat(b).as("the same pending occurrence").isEqualTo(a);
      assertThat(count("type = 'recurring-shared'")).isEqualTo(1);
    }
  }

  @Test
  void changedScheduleMovesThePendingOccurrence() throws Exception {
    var logs = LogCapture.of(JobScheduler.class);
    try (logs;
        Bikesched bikesched = node("node-recurring-change")) {
      bikesched
          .newJob("recurring-change")
          .handler(() -> {})
          .schedule(Recurrence.every(Duration.ofHours(1)));
      UUID moved =
          bikesched.newJob("recurring-change").schedule(Recurrence.every(Duration.ofHours(2)));

      assertThat(
              count(
                  "id = '"
                      + moved
                      + "' and recurrence = 'every PT2H'"
                      + " and scheduled_at > now() + interval '110 minutes'"))
          .isEqualTo(1);
      assertThat(count("type = 'recurring-change'")).isEqualTo(1);
    }
    assertThat(logs.messages())
        .anyMatch(
            m ->
                m.startsWith(
                    "Recurrence of recurring job recurring-change changed from 'every PT1H' to"
                        + " 'every PT2H', next occurrence moved to "));
  }

  @Test
  void unchangedScheduleKeepsThePendingOccurrence() throws Exception {
    try (Bikesched bikesched = node("node-recurring-same")) {
      Recurrence hourly = Recurrence.every(Duration.ofHours(1));
      Instant firstRun = Instant.now().plus(Duration.ofMinutes(5));
      UUID first = bikesched.newJob("recurring-same").handler(() -> {}).schedule(hourly, firstRun);
      UUID again = bikesched.newJob("recurring-same").schedule(hourly);

      assertThat(again).isEqualTo(first);
      assertThat(count("id = '" + first + "' and scheduled_at < now() + interval '10 minutes'"))
          .as("still at the first run given before")
          .isEqualTo(1);
    }
  }

  @Test
  void permanentlyFailedOccurrenceStillSchedulesTheNextOne() throws Exception {
    try (Bikesched bikesched = node("node-recurring-fail")) {
      bikesched
          .newJob("recurring-fail")
          .maxAttempts(1)
          .handler(
              () -> {
                throw new IllegalStateException("boom");
              })
          .schedule(Recurrence.every(Duration.ofHours(1)), Instant.now());

      awaitCount("type = 'recurring-fail' and state = 'FAILED'", 1);
      assertThat(
              count(
                  "type = 'recurring-fail' and state = 'SCHEDULED'"
                      + " and scheduled_at > now() + interval '50 minutes'"))
          .isEqualTo(1);
    }
  }

  @Test
  void fixedRateIsComputedFromThePlannedTimeNotTheRetriedSchedule() throws Exception {
    try (Bikesched bikesched = node("node-recurring-planned")) {
      Instant firstRun = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      UUID first =
          bikesched
              .newJob("recurring-planned")
              .handler(
                  job -> {
                    if (job.attempts() == 1) {
                      job.fail("later").retryAfter(Duration.ofMillis(500)); // moves scheduled_at
                    }
                  })
              .schedule(Recurrence.every(Duration.ofHours(1)), firstRun);
      assertThat(count("id = '" + first + "' and fixed_rate_at = scheduled_at")).isEqualTo(1);

      awaitState(first, "SUCCEEDED");

      assertThat(count("id = '" + first + "' and scheduled_at > fixed_rate_at"))
          .as("the retry moved the scheduled time, not the planned one")
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_jobs next, bikesched_jobs ended",
                  "ended.id = '"
                      + first
                      + "' and next.type = 'recurring-planned' and next.state = 'SCHEDULED'"
                      + " and next.fixed_rate_at = ended.fixed_rate_at + interval '1 hour'"
                      + " and next.scheduled_at = next.fixed_rate_at"))
          .as("next run = planned run + 1 hour")
          .isEqualTo(1);
    }
  }

  @Test
  void wallClockSchedulesStoreNoPlannedTime() throws Exception {
    try (Bikesched bikesched = node("node-recurring-wall-clock")) {
      UUID occurrence =
          bikesched
              .newJob("recurring-wall-clock")
              .handler(() -> {})
              .schedule(Recurrence.daily(java.time.LocalTime.NOON, java.time.ZoneOffset.UTC));

      assertThat(count("id = '" + occurrence + "' and fixed_rate_at is null")).isEqualTo(1);
    }
  }

  @Test
  void retriedAttemptDoesNotScheduleAnotherOccurrence() throws Exception {
    var attempts = new AtomicInteger();
    try (Bikesched bikesched = node("node-recurring-retry")) {
      bikesched
          .newJob("recurring-retry")
          .handler(
              () -> {
                if (attempts.incrementAndGet() == 1) {
                  throw new IllegalStateException("first attempt fails");
                }
              })
          .schedule(Recurrence.every(Duration.ofHours(1)), Instant.now());

      awaitCount("type = 'recurring-retry' and state = 'SUCCEEDED'", 1);
      assertThat(count("type = 'recurring-retry'")).as("succeeded + next").isEqualTo(2);
      assertThat(count("type = 'recurring-retry' and state = 'SUCCEEDED' and attempts = 2"))
          .isEqualTo(1);
    }
  }

  @Test
  void cancellingCancelsOnlyTheOccurrence() throws Exception {
    try (Bikesched bikesched = node("node-recurring-cancel")) {
      bikesched
          .newJob("recurring-cancel")
          .handler(job -> job.cancel("not today"))
          .schedule(Recurrence.every(Duration.ofHours(1)), Instant.now());

      awaitCount("type = 'recurring-cancel' and state = 'CANCELLED'", 1);
      assertThat(count("type = 'recurring-cancel' and state = 'SCHEDULED'")).isEqualTo(1);
    }
  }

  @Test
  void missingOccurrenceIsRecreatedOnceWhenPollingStarts() throws Exception {
    var logs = LogCapture.of(DefaultBikesched.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-recurring-repair")
                .nodeCleanUpInterval(Duration.ofMillis(200))
                .autoStartPolling(false)
                .build()) {
      UUID occurrence =
          bikesched
              .newJob("recurring-repair")
              .handler(() -> {})
              .schedule(Recurrence.every(Duration.ofHours(1)));
      execute("delete from bikesched_jobs where id = '" + occurrence + "'");

      bikesched.start();
      assertThat(count("type = 'recurring-repair' and state = 'SCHEDULED'"))
          .as("repaired before start() returns")
          .isOne();

      // Only once per node lifetime: a later missing occurrence is not recreated.
      execute("delete from bikesched_jobs where type = 'recurring-repair'");
      Thread.sleep(600);
      assertThat(count("type = 'recurring-repair'")).isZero();
    }
    assertThat(logs.messages())
        .filteredOn(m -> m.startsWith("Recurring job recurring-repair had no pending occurrence"))
        .hasSize(1);
  }

  @Test
  void removedRecurringJobIsNotRecreated() throws Exception {
    try (Bikesched bikesched = node("node-recurring-remove")) {
      bikesched
          .newJob("recurring-remove")
          .handler(() -> {})
          .schedule(Recurrence.every(Duration.ofHours(1)));

      assertThat(bikesched.removeRecurringJob("recurring-remove")).isTrue();
      Thread.sleep(300); // several heartbeats

      assertThat(count("type = 'recurring-remove'")).isZero();
      assertThat(bikesched.removeRecurringJob("recurring-remove")).isFalse();
    }
  }

  @Test
  void oneOffJobsOfTheSameTypeDoNotAffectTheRecurrence() throws Exception {
    try (Bikesched bikesched = node("node-recurring-oneoff")) {
      bikesched
          .newJob("recurring-oneoff")
          .handler(() -> {})
          .schedule(Recurrence.every(Duration.ofHours(1)));
      UUID oneOff = bikesched.newJob("recurring-oneoff").schedule();

      awaitState(oneOff, "SUCCEEDED");
      assertThat(count("type = 'recurring-oneoff'")).as("one-off + pending").isEqualTo(2);
      assertThat(count("id = '" + oneOff + "' and recurrence is null")).isEqualTo(1);
    }
  }

  @Test
  void transactionalSchedulingStillWorks() throws Exception {
    try (Bikesched bikesched = node("node-recurring-tx");
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      // connection(...) leads to a TransactionalJobBuilder, which has no schedule(Recurrence):
      // recurring jobs can't be scheduled in the caller's transaction.
      UUID jobId =
          bikesched
              .newJob("recurring-tx")
              .handler(() -> {})
              .connection(connection)
              .priority(3)
              .schedule();
      connection.commit();

      awaitState(jobId, "SUCCEEDED");
      assertThat(count("id = '" + jobId + "' and priority = 3")).isEqualTo(1);
    }
  }

  private static void awaitCount(String where, long expected) {
    await()
        .untilAsserted(() -> assertThat(count(where)).as(where).isGreaterThanOrEqualTo(expected));
  }

  @Test
  void failedRepairIsRetriedUntilItSucceeds() throws Exception {
    var failures = new AtomicInteger(2);
    Recurrence flaky =
        Recurrence.of(
            "flaky",
            previous -> {
              if (failures.getAndDecrement() > 0) {
                throw new IllegalStateException("schedule not available yet");
              }
              return previous.plus(Duration.ofHours(1));
            });
    var logs = LogCapture.of(DefaultBikesched.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-recurring-retry")
                .nodeCleanUpInterval(Duration.ofMillis(100))
                .autoStartPolling(false)
                .build()) {
      UUID occurrence =
          bikesched
              .newJob("recurring-retry")
              .handler(() -> {})
              .schedule(flaky, Instant.now().plus(Duration.ofHours(1)));
      execute("delete from bikesched_jobs where id = '" + occurrence + "'");

      bikesched.start();
      assertThat(count("type = 'recurring-retry'")).as("first attempt failed").isZero();

      awaitCount("type = 'recurring-retry' and state = 'SCHEDULED'", 1);
    }
    assertThat(logs.messages())
        .filteredOn(m -> m.equals("Checking recurring job recurring-retry failed"))
        .hasSize(2);
    assertThat(logs.messages())
        .filteredOn(m -> m.equals("Repairing recurring jobs failed, retrying in PT0.1S"))
        .hasSize(2);
    assertThat(logs.messages())
        .filteredOn(m -> m.startsWith("Recurring job recurring-retry had no pending occurrence"))
        .hasSize(1);
  }

  private static Bikesched node(String name) {
    return testNode(name).nodeCleanUpInterval(Duration.ofMillis(50)).build();
  }
}
