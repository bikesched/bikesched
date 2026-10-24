// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.RetryBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Polls: {@link Job#newPoll(String, Class, Duration)} and its {@link RetryBackoff} variant. */
class PollTest extends AbstractBikeschedTest {

  private static final Duration SHORT = Duration.ofMillis(100);

  /** Deletes the polling jobs left behind, so later tests' nodes don't claim them. */
  @AfterEach
  void deleteJobs() throws Exception {
    execute("delete from bikesched_jobs where name like 'poll-%'");
  }

  @Test
  void aPollChecksUntilReadyInOneStepWithoutUsingUpAttempts() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    var logs = LogCapture.of(Job.class);
    try (logs;
        Bikesched bikesched = testNode("node-poll").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-basic")
              .handler(
                  job -> {
                    job.step(
                        "before",
                        Void.class,
                        ignored -> {
                          calls.add("before");
                          return null;
                        });
                    String report =
                        job.newPoll("await-report", String.class, SHORT)
                            .run(
                                step -> {
                                  calls.add("check " + step.attempts());
                                  return step.attempts() < 3
                                      ? Poll.notReady()
                                      : Poll.ready("\"report\"");
                                });
                    calls.add("after " + report);
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(calls)
          .containsExactly("before", "check 1", "check 2", "check 3", "after \"report\"");
      assertThat(
              countSteps(
                  jobId,
                  "name = 'await-report' and type = 'POLL' and state = 'SUCCEEDED'"
                      + " and attempts = 3 and result = '\"report\"'"))
          .isEqualTo(1);
      assertThat(countSteps(jobId, "true")).as("one row for all checks").isEqualTo(2);
      assertThat(count("id = '" + jobId + "' and attempts = 1"))
          .as("checks that are not ready don't use up attempts")
          .isEqualTo(1);
      assertThat(logs.messages())
          .anyMatch(
              m ->
                  m.startsWith(
                      "Step await-report of job " + jobId + ": check 1 not ready, checking again"))
          .anyMatch(
              m -> m.startsWith("Step await-report of job " + jobId + " ready after check 3"));
    }
  }

  @Test
  void aLaterRunReturnsTheStoredValueWithoutChecking() throws Exception {
    var checks = new AtomicInteger();
    List<String> values = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-stored").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-stored")
              .handler(
                  job -> {
                    values.add(
                        job.newPoll("p", String.class, SHORT)
                            .run(
                                ignored ->
                                    Poll.ready("\"value " + checks.incrementAndGet() + "\"")));
                    if (job.attempts() == 1) {
                      throw new IllegalStateException("fails once after the poll");
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(checks).hasValue(1);
      assertThat(values).containsExactly("\"value 1\"", "\"value 1\"");
    }
  }

  @Test
  void aCheckThatIsNotReadySuspendsTheJobUntilTheNextCheck() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-waiting").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-waiting")
              .handler(
                  job ->
                      job.newPoll("p", String.class, Duration.ofHours(1))
                          .run(ignored -> Poll.notReady()))
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(
              countSteps(
                  jobId,
                  "name = 'p' and state = 'WAITING' and locked_by_node_id is null and attempts = 1"
                      + " and wake_at > now() + interval '50 minutes' and deadline_at is null"))
          .isEqualTo(1);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 0 and scheduled_at = (select wake_at from"
                      + " bikesched_steps where job_id = '"
                      + jobId
                      + "' and name = 'p')"))
          .as("due at the next check, attempt given back")
          .isEqualTo(1);
    }
  }

  @Test
  void aBackoffIntervalGetsTheNumberOfEachCheckThatWasNotReady() throws Exception {
    List<Integer> backoffCalls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-backoff").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-backoff")
              .maxAttempts(2)
              .handler(
                  job ->
                      job.newPoll(
                              "p",
                              String.class,
                              check -> {
                                backoffCalls.add(check);
                                return Duration.ofMillis(50);
                              })
                          .run(
                              step ->
                                  switch (step.attempts()) {
                                    case 1 -> throw new IllegalStateException("outage");
                                    case 2, 3 -> Poll.notReady();
                                    default -> Poll.ready("\"done\"");
                                  }))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(backoffCalls)
          .as("not called for the failed check 1 nor the ready check 4")
          .containsExactly(2, 3);
      assertThat(countSteps(jobId, "name = 'p' and attempts = 4")).isEqualTo(1);
      assertThat(count("id = '" + jobId + "' and attempts = 2"))
          .as("only the failed check used up an attempt")
          .isEqualTo(1);
    }
  }

  @Test
  void notReadyWithRetryAfterOverridesTheIntervalForOneCheck() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-retry-after").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-retry-after")
              .handler(
                  job ->
                      job.newPoll("p", String.class, SHORT)
                          .run(ignored -> Poll.notReady(Duration.ofHours(2))))
              .schedule();

      awaitState(jobId, "WAITING");
      assertThat(countSteps(jobId, "wake_at > now() + interval '110 minutes'")).isEqualTo(1);
    }
  }

  @Test
  void aFailingIntervalIsLoggedAndTheNextCheckRunsRightAway() throws Exception {
    var logs = LogCapture.of(Job.class);
    try (logs;
        Bikesched bikesched = testNode("node-poll-broken-backoff").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-broken-backoff")
              .handler(
                  job ->
                      job.newPoll(
                              "p",
                              String.class,
                              ignored -> {
                                throw new IllegalStateException("broken");
                              })
                          .run(step -> step.attempts() < 2 ? Poll.notReady() : Poll.ready(null)))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(logs.messages())
          .contains(
              "Interval of poll p of job "
                  + jobId
                  + " failed after check 1, checking again right away");
    }
  }

  @Test
  void aTimeoutWithoutHandlerFailsTheJobAfterALastCheckAtTheDeadline() throws Exception {
    var checks = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-poll-timeout").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-timeout")
              .handler(
                  job ->
                      job.newPoll("p", String.class, Duration.ofHours(1))
                          .timeout(Duration.ofMillis(300))
                          .run(
                              ignored -> {
                                checks.incrementAndGet();
                                return Poll.notReady();
                              }))
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(checks).as("the first check and the last one at the deadline").hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and last_error = 'Polling in step ''p'' timed out"
                      + " after PT0.3S (2 checks)'"))
          .as("no retry")
          .isEqualTo(1);
      assertThat(
              countSteps(
                  jobId,
                  "name = 'p' and state = 'TIMED_OUT' and wake_at = deadline_at"
                      + " and finished_at >= deadline_at"))
          .isEqualTo(1);
    }
  }

  @Test
  void aFallbackResolvedAfterTheTimeoutIsStoredAndReturnedOnLaterRuns() throws Exception {
    List<String> results = new CopyOnWriteArrayList<>();
    var fallbacks = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-poll-fallback").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-fallback")
              .handler(
                  job -> {
                    results.add(
                        job.newPoll("p", String.class, SHORT)
                            .timeout(Duration.ofMillis(200))
                            .onFailure(
                                failing ->
                                    failing.resolve(
                                        "\"fallback " + fallbacks.incrementAndGet() + "\""))
                            .run(ignored -> Poll.notReady()));
                    job.sleep("replay", Duration.ofMillis(100));
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(results).containsExactly("\"fallback 1\"", "\"fallback 1\"");
      assertThat(
              countSteps(
                  jobId,
                  "name = 'p' and state = 'SUCCEEDED' and result = '\"fallback 1\"'::jsonb"
                      + " and last_error like 'org.bikesched.job.PollTimeoutException: Polling in"
                      + " step ''p'' timed out after PT0.2S%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void theStoredDeadlineAndNextCheckWinAndAnEarlyClaimDoesNotCheck() throws Exception {
    var timeout = new AtomicReference<>(Duration.ofHours(1));
    var runs = new AtomicInteger();
    var checks = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-poll-changed").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-changed")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    job.newPoll("p", String.class, Duration.ofHours(1))
                        .timeout(timeout.get())
                        .run(
                            ignored -> {
                              checks.incrementAndGet();
                              return Poll.notReady();
                            });
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      // A shorter timeout on the next run, which is forced early (claimed before its next check).
      timeout.set(Duration.ofMillis(1));
      execute("update bikesched_jobs set scheduled_at = now() where id = '" + jobId + "'");
      await().until(() -> runs.get() == 2);
      awaitState(jobId, "WAITING");

      assertThat(checks).as("no check before it is due").hasValue(1);
      assertThat(
              countSteps(
                  jobId,
                  "name = 'p' and state = 'WAITING' and attempts = 1"
                      + " and deadline_at > now() + interval '50 minutes'"
                      + " and wake_at > now() + interval '50 minutes'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aCheckThatThrowsFailsTheAttemptAndTheNextAttemptChecksRightAway() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-throws").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-throws")
              .maxAttempts(2)
              .handler(
                  job ->
                      job.newPoll("p", String.class, Duration.ofHours(1))
                          .run(
                              step -> {
                                if (step.attempts() == 1) {
                                  throw new IllegalStateException("service unavailable");
                                }
                                return Poll.ready("\"ok\"");
                              }))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(count("id = '" + jobId + "' and attempts = 2")).isEqualTo(1);
      assertThat(countSteps(jobId, "name = 'p' and state = 'SUCCEEDED' and attempts = 2"))
          .isEqualTo(1);
    }
  }

  @Test
  void aCheckThatThrowsInTheLastAttemptFailsTheJob() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-throws-last").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-throws-last")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newPoll("p", String.class, SHORT)
                          .run(
                              ignored -> {
                                throw new IllegalStateException("service unavailable");
                              }))
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              countSteps(
                  jobId,
                  "name = 'p' and state = 'FAILED' and locked_by_node_id is null"
                      + " and last_error = 'java.lang.IllegalStateException: service unavailable'"))
          .isEqualTo(1);
    }
  }

  @Test
  void cancellingAPollingJobCancelsThePollAndCompensates() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-cancel").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-cancel")
              .handler(
                  job -> {
                    job.newStep("book", Void.class)
                        .compensateWith((step, result) -> calls.add("compensate book"))
                        .run(ignored -> null);
                    job.newPoll("p", String.class, Duration.ofHours(1))
                        .run(ignored -> Poll.notReady());
                    calls.add("after");
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      assertThat(bikesched.cancel(jobId)).isTrue();

      awaitState(jobId, "CANCELLED");
      assertThat(calls).containsExactly("compensate book");
      assertThat(countSteps(jobId, "name = 'p' and state = 'CANCELLED'")).isEqualTo(1);
    }
  }

  @Test
  void compensationReplayPassesCompletedAndTimedOutPollsWithoutChecking() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-compensate").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-compensate")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newStep("book", Void.class)
                        .compensateWith((step, result) -> calls.add("compensate book"))
                        .run(ignored -> null);
                    String ready =
                        job.newPoll("ready", String.class, SHORT)
                            .run(
                                ignored -> {
                                  calls.add("check ready");
                                  return Poll.ready("\"r\"");
                                });
                    String fallback =
                        job.newPoll("timed-out", String.class, SHORT)
                            .timeout(SHORT)
                            .onFailure(failing -> failing.resolve("\"fallback\""))
                            .run(
                                ignored -> {
                                  calls.add("check timed-out");
                                  return Poll.notReady();
                                });
                    job.newStep("ship", String.class)
                        .compensateWith((step, result) -> calls.add("compensate ship " + result))
                        .run(
                            ignored ->
                                "\""
                                    + ready.replace("\"", "")
                                    + "-"
                                    + fallback.replace("\"", "")
                                    + "\"");
                    if (!job.isCompensating()) {
                      throw new IllegalStateException("fails permanently");
                    }
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(calls)
          .containsExactly(
              "check ready",
              "check timed-out",
              "check timed-out",
              "compensate ship \"r-fallback\"",
              "compensate book");
    }
  }

  @Test
  void compensationReplayEndsAtAnUnfinishedPoll() throws Exception {
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-compensate-waiting").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-compensate-waiting")
              .handler(
                  job -> {
                    job.newStep("book", Void.class)
                        .compensateWith((step, result) -> calls.add("compensate book"))
                        .run(ignored -> null);
                    job.newPoll("p", String.class, Duration.ofHours(1))
                        .run(
                            ignored -> {
                              calls.add("check");
                              return Poll.notReady();
                            });
                    calls.add("after the poll");
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.cancel(jobId);

      awaitState(jobId, "CANCELLED");
      assertThat(calls).containsExactly("check", "compensate book");
    }
  }

  @Test
  void pollingInsideAStepIsRejected() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-nested").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-nested")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.step(
                          "outer",
                          String.class,
                          ignored ->
                              job.newPoll("p", String.class, SHORT)
                                  .run(ignored2 -> Poll.ready(null))))
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like '%cannot create poll ''p'' while step ''outer''"
                      + " runs%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aTimedOutEventWaitFallsBackToAPoll() throws Exception {
    String eventName = "poll-event-" + UUID.randomUUID();
    List<String> results = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-event").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-event")
              .handler(
                  job -> {
                    String webhook =
                        job.waitForEvent("webhook", eventName, "k", String.class)
                            .timeout(SHORT)
                            .onFailure(failing -> failing.resolve(null))
                            .receive();
                    results.add(
                        webhook != null
                            ? webhook
                            : job.newPoll("p", String.class, SHORT)
                                .run(
                                    step ->
                                        step.attempts() < 2
                                            ? Poll.notReady()
                                            : Poll.ready("\"polled\"")));
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(results).containsExactly("\"polled\"");
      assertThat(countSteps(jobId, "name = 'p' and state = 'SUCCEEDED' and attempts = 2"))
          .isEqualTo(1);
    }
  }

  @Test
  void aStepNameOfAnotherKindIsRejected() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-clash").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-clash")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.step("same", Void.class, ignored -> null);
                    job.newPoll("same", String.class, SHORT).run(ignored -> Poll.ready(null));
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like '%Step ''same'' of job % is a regular step, not a"
                      + " poll%'"))
          .isEqualTo(1);
    }
  }

  @Test
  void invalidArgumentsAreRejected() throws Exception {
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-poll-args").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-args")
              .handler(
                  job -> {
                    for (Runnable call :
                        List.<Runnable>of(
                            () -> job.newPoll("p", String.class, Duration.ZERO),
                            () -> job.newPoll(" ", String.class, SHORT),
                            () -> job.newPoll("p", String.class, SHORT).timeout(Duration.ZERO),
                            () -> Poll.notReady(Duration.ofSeconds(-1)),
                            () -> job.newPoll("p", String.class, (RetryBackoff) null))) {
                      try {
                        call.run();
                      } catch (RuntimeException e) {
                        errors.add(e);
                      }
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(errors).hasSize(5);
      assertThat(errors.get(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("interval must be positive: PT0S");
      assertThat(errors.get(1)).isInstanceOf(IllegalArgumentException.class);
      assertThat(errors.get(2))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("timeout must be positive: PT0S");
      assertThat(errors.get(3))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("retryAfter must not be negative: PT-1S");
      assertThat(errors.get(4)).isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  void aPollRecordsItsFirstWaitAndItsOutcomeInTheJobHistory() {
    try (Bikesched bikesched =
        testNode("node-poll-history").jobHistory(Duration.ofHours(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("poll-history")
              .handler(
                  job ->
                      job.newPoll("p", String.class, SHORT)
                          .run(step -> step.attempts() < 3 ? Poll.notReady() : Poll.ready(null)))
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.findJobHistory(jobId))
          .extracting(
              entry ->
                  (entry.stepName() == null ? "job" : entry.stepName())
                      + " "
                      + entry.state()
                      + (entry.stepName() == null && !entry.state().equals("WAITING")
                          ? ""
                          : " " + entry.detail()))
          .containsExactly(
              "job SCHEDULED",
              "job RUNNING",
              "job WAITING suspended at step 'p', polling",
              "p SUCCEEDED ready after 3 checks",
              "job SUCCEEDED");
    }
  }

  @Test
  void aFailedCheckAndATimeoutAreRecordedInTheJobHistory() {
    try (Bikesched bikesched =
        testNode("node-poll-history-failure").jobHistory(Duration.ofHours(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("poll-history-failure")
              .maxAttempts(2)
              .handler(
                  job ->
                      job.newPoll("p", String.class, Duration.ofMillis(50))
                          .timeout(Duration.ofMillis(300))
                          .run(
                              step -> {
                                if (step.attempts() == 2) {
                                  throw new IllegalStateException("outage");
                                }
                                return Poll.notReady();
                              }))
              .schedule();
      awaitState(jobId, "FAILED");

      assertThat(bikesched.findJobHistory(jobId))
          .filteredOn(entry -> entry.stepName() != null)
          .extracting(entry -> entry.state() + " " + entry.detail())
          .first()
          .isEqualTo("FAILED check 2 failed: java.lang.IllegalStateException: outage");
      assertThat(bikesched.findJobHistory(jobId))
          .filteredOn(entry -> entry.stepName() != null)
          .extracting(entry -> entry.state() + " " + entry.detail())
          .last()
          .asString()
          .matches("TIMED_OUT timed out after \\d+ checks");
    }
  }

  @Test
  void theSchemaChecksPollSteps() throws Exception {
    try (Bikesched bikesched = testNode("node-poll-schema").build()) {
      UUID jobId = bikesched.newJob("poll-schema").handler(job -> {}).schedule(Duration.ofDays(1));

      // a poll: WAITING until its next check, with a deadline
      insertStep(jobId, "poll", "POLL", "WAITING", "now()", "now()");
      // a waiting poll without its next check
      assertThatThrownBy(() -> insertStep(jobId, "no-check", "POLL", "WAITING", "null", "null"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("bikesched_steps_wait_check");
      // only polls have a deadline of their own
      assertThatThrownBy(() -> insertStep(jobId, "sleep", "SLEEP", "WAITING", "now()", "now()"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("bikesched_steps_wait_check");
      // polls time out
      insertStep(jobId, "timed-out", "POLL", "TIMED_OUT", "null", "now()");
    }
  }

  private static void insertStep(
      UUID jobId, String name, String type, String state, String wakeAt, String deadlineAt)
      throws SQLException {
    String finishedAt = state.equals("WAITING") ? "null" : "now()";
    execute(
        "insert into bikesched_steps"
            + " (id, job_id, name, type, state, wake_at, deadline_at, finished_at)"
            + " values (gen_random_uuid(), '"
            + jobId
            + "', '"
            + name
            + "', '"
            + type
            + "', '"
            + state
            + "', "
            + wakeAt
            + ", "
            + deadlineAt
            + ", "
            + finishedAt
            + ")");
  }

  private static long countSteps(UUID jobId, String where) throws SQLException {
    return countRows("bikesched_steps", "job_id = '" + jobId + "' and " + where);
  }
}
