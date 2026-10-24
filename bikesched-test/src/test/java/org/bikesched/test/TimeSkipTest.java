// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.bikesched.job.Poll;
import org.bikesched.job.Recurrence;
import org.bikesched.job.TypedJob;
import org.junit.jupiter.api.Test;

/** Tests {@link BikeschedTest#fastForward} and {@link BikeschedTest#advanceTime}. */
class TimeSkipTest extends AbstractTestModuleTest {

  private static final Duration DAY = Duration.ofDays(1);

  @Test
  void fastForwardEndsASleep() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("sleeps");
      bikesched.registerJobHandler(
          type,
          job -> {
            job.sleep("nap", DAY);
            job.step("after-nap", () -> {});
          });
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.WAITING);

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isTrue();

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceededSteps("nap", "after-nap");
      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
    }
  }

  @Test
  void fastForwardRunsTheNextCheckOfAPollBeforeItsDeadline() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("polls");
      var checks = new AtomicInteger();
      bikesched.registerJobHandler(
          type,
          job ->
              job.newPoll("report", Object.class, Duration.ofHours(1))
                  .timeout(DAY)
                  .run(step -> checks.incrementAndGet() == 1 ? Poll.notReady() : Poll.ready(null)));
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.WAITING);

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isTrue();

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      assertThat(checks).hasValue(2);
    }
  }

  @Test
  void fastForwardTimesOutAnEventWait() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("waits");
      String event = uniqueType("payment-received");
      var timedOut = new AtomicInteger();
      bikesched.registerJobHandler(
          type,
          job ->
              job.waitForEvent("payment", event, "order-1", Object.class)
                  .timeout(DAY)
                  .onFailure(
                      failing -> {
                        timedOut.incrementAndGet();
                        failing.resolve(null);
                      })
                  .receive());
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.WAITING);

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isTrue();

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      assertThat(timedOut).hasValue(1);
    }
  }

  @Test
  void fastForwardRetriesWhileAWaitingStepIsLocked() throws Exception {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("sleeps");
      bikesched.registerJobHandler(type, job -> job.sleep("nap", DAY));
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.WAITING);

      CompletableFuture<Boolean> fastForward;
      try (var connection = DATA_SOURCE.getConnection()) {
        connection.setAutoCommit(false);
        try (var lock =
            connection.prepareStatement(
                "select 1 from bikesched_steps where job_id = ? for update")) {
          lock.setObject(1, jobId);
          lock.executeQuery().close();
        }
        fastForward =
            CompletableFuture.supplyAsync(() -> BikeschedTest.fastForward(DATA_SOURCE, jobId));
        Thread.sleep(300);
        assertThat(fastForward).isNotDone();
        connection.commit();
      }

      assertThat(fastForward.get()).isTrue();
      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
    }
  }

  @Test
  void fastForwardRunsADelayedJob() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("delayed");
      bikesched.registerJobHandler(type, job -> {});
      UUID jobId = bikesched.newJob(type).schedule(DAY);

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isTrue();

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
    }
  }

  @Test
  void fastForwardChangesNothingForAJobWaitingWithoutADeadline() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("waits-forever");
      String event = uniqueType("never-published");
      bikesched.registerJobHandler(
          type, job -> job.waitForEvent("payment", event, "order-1", Object.class).receive());
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.WAITING);

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isFalse();
      assertThat(BikeschedTest.fastForward(DATA_SOURCE, UUID.randomUUID())).isFalse();

      bikesched.cancel(jobId);
      BikeschedTest.awaitJob(bikesched, jobId).untilEnded();
    }
  }

  @Test
  void fastForwardChangesNothingForAnEndedJob() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("ended");
      bikesched.registerJobHandler(type, job -> {});
      UUID jobId = bikesched.newJob(type).schedule();
      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();

      assertThat(BikeschedTest.fastForward(DATA_SOURCE, jobId)).isFalse();
    }
  }

  @Test
  void advanceTimeRunsWhatIsDueWithinTheDurationAndMovesTheRestCloser() throws SQLException {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("advanced");
      bikesched.registerJobHandler(type, job -> {});
      UUID soon = bikesched.newJob(type).schedule(Duration.ofHours(1));
      UUID later = bikesched.newJob(type).schedule(Duration.ofHours(3));

      assertThat(BikeschedTest.advanceTime(DATA_SOURCE, Duration.ofHours(2), type)).isEqualTo(2);

      BikeschedTest.awaitJob(bikesched, soon).untilSucceeded();
      assertThat(bikesched.findJob(later).orElseThrow().state()).isEqualTo(JobState.SCHEDULED);
      assertThat(secondsUntilScheduled(later)).isBetween(3500L, 3600L);

      assertThat(BikeschedTest.advanceTime(DATA_SOURCE, Duration.ofHours(1), type)).isEqualTo(1);

      BikeschedTest.awaitJob(bikesched, later).untilSucceeded();
    }
  }

  @Test
  void advanceTimeLeavesOtherTypesAlone() throws SQLException {
    try (Bikesched bikesched = buildNode("time-skip")) {
      var advanced = TypedJob.of(uniqueType("advanced"), Object.class);
      String other = uniqueType("other");
      bikesched.registerJobHandler(advanced.name(), job -> {});
      bikesched.registerJobHandler(other, job -> {});
      UUID advancedJob = bikesched.newJob(advanced).schedule(DAY);
      UUID otherJob = bikesched.newJob(other).schedule(DAY);

      assertThat(BikeschedTest.advanceTime(DATA_SOURCE, DAY, advanced)).isEqualTo(1);

      BikeschedTest.awaitJob(bikesched, advancedJob).untilSucceeded();
      assertThat(secondsUntilScheduled(otherJob)).isGreaterThan(DAY.toSeconds() - 60);

      deleteJobs(other);
    }
  }

  @Test
  void advanceTimeRunsTheNextOccurrenceOfARecurringJob() {
    try (Bikesched bikesched = buildNode("time-skip")) {
      String type = uniqueType("daily");
      var runs = new AtomicInteger();
      bikesched.registerJobHandler(type, job -> runs.incrementAndGet());
      bikesched.newJob(type).schedule(Recurrence.every(DAY));

      assertThat(BikeschedTest.advanceTime(DATA_SOURCE, DAY, type)).isEqualTo(1);

      await().untilAsserted(() -> assertThat(runs).hasValue(1));
      bikesched.removeRecurringJob(type);
    }
  }

  @Test
  void advanceTimeRejectsANegativeDuration() {
    assertThatThrownBy(
            () -> BikeschedTest.advanceTime(DATA_SOURCE, Duration.ofSeconds(-1), "any-type"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duration must not be negative: PT-1S");
  }

  private static long secondsUntilScheduled(UUID jobId) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement =
            connection.prepareStatement(
                "select extract(epoch from scheduled_at - now())::bigint"
                    + " from bikesched_jobs where id = ?")) {
      statement.setObject(1, jobId);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static void deleteJobs(String type) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.prepareStatement("delete from bikesched_jobs where name = ?")) {
      statement.setString(1, type);
      statement.execute();
    }
  }
}
