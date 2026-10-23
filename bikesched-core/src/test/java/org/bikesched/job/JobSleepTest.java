// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Durable sleeps: {@link Job#sleep(String, Duration)} and {@link Job#sleepUntil}. */
class JobSleepTest extends AbstractBikeschedTest {

  /** Deletes the sleeping jobs left behind, so later tests' nodes don't claim them. */
  @AfterEach
  void deleteJobs() throws Exception {
    execute("delete from bikesched_jobs where type like 'sleep-%'");
  }

  @Test
  void sleepSuspendsTheJobAndContinuesAfterTheDuration() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    var logs = LogCapture.of(Job.class);
    try (logs;
        Bikesched bikesched = testNode("node-sleep").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-basic")
              .handler(
                  job -> {
                    job.step(
                        "before",
                        Void.class,
                        ignored -> {
                          calls.add("before");
                          return null;
                        });
                    job.sleep("nap", Duration.ofMillis(500));
                    calls.add("after");
                  })
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(countSteps(jobId, "name = 'nap' and state = 'WAITING' and wake_at > now()"))
          .isEqualTo(1);
      assertThat(count("id = '" + jobId + "' and attempts = 0"))
          .as("sleeping gives the attempt back")
          .isEqualTo(1);
      assertThat(calls).containsExactly("before");

      awaitState(jobId, "SUCCEEDED");
      assertThat(calls).containsExactly("before", "after");
      assertThat(countSteps(jobId, "name = 'nap' and state = 'SUCCEEDED' and wake_at is not null"))
          .isEqualTo(1);
      assertThat(logs.messages())
          .anyMatch(m -> m.startsWith("Step nap of job " + jobId + " sleeping until "))
          .anyMatch(m -> m.startsWith("Step nap of job " + jobId + " slept until "));
    }
  }

  @Test
  void sleepingAndWakingUpAreRecordedInTheJobHistory() {
    try (Bikesched bikesched =
        testNode("node-sleep-history").jobHistory(Duration.ofHours(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-history")
              .handler(job -> job.sleep("nap", Duration.ofMillis(200)))
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.findJobHistory(jobId))
          .extracting(
              entry ->
                  (entry.stepName() == null ? "job" : entry.stepName())
                      + " "
                      + entry.state()
                      + " "
                      + (entry.detail() == null
                          ? ""
                          : entry.detail().replaceAll("until .*", "until")))
          .containsSubsequence(
              "nap WAITING sleeping until",
              "job WAITING suspended at step 'nap'",
              "job RUNNING ",
              "nap SUCCEEDED woke up",
              "job SUCCEEDED ");
    }
  }

  @Test
  void sleepUntilAPassedTimeReturnsRightAway() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-sleep-past").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-past")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    job.sleepUntil("past", Instant.now().minusSeconds(60));
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(runs).hasValue(1);
      assertThat(count("id = '" + jobId + "' and attempts = 1")).isEqualTo(1);
      assertThat(countSteps(jobId, "name = 'past' and state = 'SUCCEEDED'")).isEqualTo(1);
    }
  }

  @Test
  void theStoredWakeUpTimeWinsOverAChangedArgument() throws Exception {
    var duration = new AtomicReference<>(Duration.ofHours(1));
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-sleep-changed").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-changed")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    job.sleep("nap", duration.get());
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      // A shorter sleep on the next run, which is forced early (e.g. claimed before its time).
      duration.set(Duration.ofMillis(1));
      execute("update bikesched_jobs set scheduled_at = now() where id = '" + jobId + "'");
      await().until(() -> runs.get() == 2);
      awaitState(jobId, "WAITING");

      assertThat(countSteps(jobId, "name = 'nap' and wake_at > now() + interval '50 minutes'"))
          .as("still sleeping until the first run's wake-up time")
          .isEqualTo(1);
      assertThat(count("id = '" + jobId + "' and scheduled_at > now() + interval '50 minutes'"))
          .isEqualTo(1);
    }
  }

  @Test
  void cancellingASleepingJobCancelsTheSleepAndCompensates() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-sleep-cancel").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-cancel")
              .handler(
                  job -> {
                    job.newStep("book", Void.class)
                        .compensateWith((step, result) -> calls.add("compensate book"))
                        .run(ignored -> null);
                    job.sleep("nap", Duration.ofHours(1));
                    calls.add("after");
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      assertThat(bikesched.cancel(jobId)).isTrue();

      awaitState(jobId, "CANCELLED");
      assertThat(calls).containsExactly("compensate book");
      assertThat(countSteps(jobId, "name = 'nap' and state = 'CANCELLED'")).isEqualTo(1);
    }
  }

  @Test
  void sleepingInsideAStepIsRejected() throws Exception {
    try (Bikesched bikesched = testNode("node-sleep-nested").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-nested")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.step(
                          "outer",
                          Void.class,
                          ignored -> {
                            job.sleep("nap", Duration.ofMinutes(1));
                            return null;
                          }))
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like '%cannot sleep as step ''nap'' while step ''outer''"
                      + " runs%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aStepNameOfARegularStepIsRejected() throws Exception {
    try (Bikesched bikesched = testNode("node-sleep-clash").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-clash")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.step("same", Void.class, ignored -> null);
                    job.sleep("same", Duration.ofMinutes(1));
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like '%Step ''same'' of job % is a regular step, not a"
                      + " sleep%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void invalidArgumentsAreRejected() throws Exception {
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-sleep-args").build()) {
      UUID jobId =
          bikesched
              .newJob("sleep-args")
              .handler(
                  job -> {
                    for (Runnable call :
                        List.<Runnable>of(
                            () -> job.sleep("nap", Duration.ZERO),
                            () -> job.sleep(" ", Duration.ofMinutes(1)),
                            () -> job.sleepUntil("nap", null))) {
                      try {
                        call.run();
                      } catch (RuntimeException e) {
                        errors.add(e);
                      }
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(errors).hasSize(3);
      assertThat(errors.get(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("duration must be positive: PT0S");
      assertThat(errors.get(1)).isInstanceOf(IllegalArgumentException.class);
      assertThat(errors.get(2)).isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  void onlySleepStepsMayWaitWithoutAnEvent() throws Exception {
    try (Bikesched bikesched = testNode("node-sleep-schema").build()) {
      UUID jobId = bikesched.newJob("sleep-schema").handler(job -> {}).schedule(Duration.ofDays(1));

      // a sleep: WAITING with a wake-up time
      insertStep(jobId, "sleep", "SLEEP", "'WAITING'", "now()");
      // a sleep without a wake-up time
      assertThatThrownBy(() -> insertStep(jobId, "no-wake-up", "SLEEP", "'WAITING'", "null"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("bikesched_steps_wait_check");
      // regular steps do not wait
      assertThatThrownBy(() -> insertStep(jobId, "step", "STEP", "'WAITING'", "now()"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("bikesched_steps_wait_check");
      // only event waits time out
      assertThatThrownBy(() -> insertStep(jobId, "timed-out", "SLEEP", "'TIMED_OUT'", "now()"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("bikesched_steps_event_check");
    }
  }

  private static void insertStep(
      UUID jobId, String name, String kind, String state, String waitUntil) throws SQLException {
    execute(
        "insert into bikesched_steps (id, job_id, name, kind, state, wake_at)"
            + " values (gen_random_uuid(), '"
            + jobId
            + "', '"
            + name
            + "', '"
            + kind
            + "', "
            + state
            + ", "
            + waitUntil
            + ")");
  }

  private static long countSteps(UUID jobId, String where) throws SQLException {
    return countRows("bikesched_steps", "job_id = '" + jobId + "' and " + where);
  }
}
