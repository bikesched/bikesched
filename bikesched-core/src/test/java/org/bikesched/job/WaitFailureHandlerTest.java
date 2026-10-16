// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.event.EventType;
import org.junit.jupiter.api.Test;

/**
 * Failure handlers of event waits and polls ({@code onFailure}): they handle a timeout right away
 * and a poll check failing in the job's last attempt, and may resolve the step with a stored
 * fallback.
 */
class WaitFailureHandlerTest extends AbstractBikeschedTest {

  private static final Duration SHORT = Duration.ofMillis(200);

  private static final String WAIT_TIMEOUT =
      "org.bikesched.event.EventTimeoutException: Waiting for event %s/k in step 'await' timed out"
          + " after PT0.2S";

  @Test
  void aResolvedWaitReturnsItsStoredFallbackRightAwayAndOnLaterRuns() throws Exception {
    String event = "wait-failure-resolve-" + UUID.randomUUID();
    List<String> results = new CopyOnWriteArrayList<>();
    List<String> errors = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-wait-failure-resolve").jobHistory(Duration.ofHours(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-resolve")
              .maxAttempts(5)
              .handler(
                  job -> {
                    results.add(
                        job.waitForEvent("await", event, "k", String.class)
                            .timeout(SHORT)
                            .onFailure(
                                failing -> {
                                  errors.add(failing.error());
                                  failing.resolve("\"missing\"");
                                })
                            .receive());
                    job.sleep("replay", Duration.ofMillis(100));
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(results).containsExactly("\"missing\"", "\"missing\"");
      assertThat(errors)
          .as("runs once, right away in the first attempt")
          .containsExactly(WAIT_TIMEOUT.formatted(event));
      assertThat(
              countSteps(
                  jobId,
                  "name = 'await' and state = 'SUCCEEDED' and result = '\"missing\"'::jsonb"
                      + " and event_id is null and failure_attempts = 1 and last_error is not null"))
          .isOne();
      assertThat(
              bikesched.findJobHistory(jobId).stream()
                  .filter(entry -> "await".equals(entry.stepName()))
                  .map(JobHistoryEntry::state))
          .containsExactly("WAITING", "TIMED_OUT", "FAILING", "SUCCEEDED");
    }
  }

  @Test
  void aTypedWaitCanBeResolvedWithNull() throws Exception {
    EventType<String> type = EventType.of("wait-failure-null-" + UUID.randomUUID(), String.class);
    List<String> results = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-wait-failure-null").build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-null")
              .handler(
                  job ->
                      results.add(
                          String.valueOf(
                              job.waitForEvent("await", type, "k")
                                  .timeout(SHORT)
                                  .onFailure(failing -> failing.resolve(null))
                                  .receive())))
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(results).containsExactly("null");
      assertThat(countSteps(jobId, "name = 'await' and state = 'SUCCEEDED' and result is null"))
          .isOne();
    }
  }

  @Test
  void anUnresolvedWaitFailsTheJobWithoutFurtherAttempts() throws Exception {
    String event = "wait-failure-fail-" + UUID.randomUUID();
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-wait-failure-fail").build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-fail")
              .maxAttempts(5)
              .handler(
                  job ->
                      job.waitForEvent("await", event, "k", String.class)
                          .timeout(SHORT)
                          .onFailure(failing -> runs.incrementAndGet())
                          .receive())
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(runs).hasValue(1);
      assertThat(countSteps(jobId, "name = 'await' and state = 'FAILED'")).isOne();
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and last_error = 'Waiting for event "
                      + event
                      + "/k in step ''await'' timed out after PT0.2S'"))
          .isOne();
    }
  }

  @Test
  void aWaitsOwnTimeoutWithoutFailureHandlerFailsTheJob() throws Exception {
    String event = "wait-failure-none-" + UUID.randomUUID();
    try (Bikesched bikesched = testNode("node-wait-failure-none").build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-none")
              .maxAttempts(5)
              .handler(
                  job ->
                      job.waitForEvent("await", event, "k", String.class).timeout(SHORT).receive())
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(countSteps(jobId, "name = 'await' and state = 'TIMED_OUT'")).isOne();
      assertThat(count("id = '" + jobId + "' and attempts = 1")).isOne();
    }
  }

  @Test
  void theFailureHandlerHandlesTheDefaultEventTimeout() throws Exception {
    String event = "wait-failure-default-" + UUID.randomUUID();
    List<String> errors = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-wait-failure-default").defaultEventTimeout(SHORT).build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-default")
              .handler(
                  job ->
                      job.waitForEvent("await", event, "k", String.class)
                          .onFailure(
                              failing -> {
                                errors.add(failing.error());
                                failing.resolve(null);
                              })
                          .receive())
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(errors)
          .containsExactly(WAIT_TIMEOUT.formatted(event) + " (default event timeout)");
    }
  }

  @Test
  void aFailureHandlerThatThrowsRunsAgainInAnExtraAttempt() throws Exception {
    String event = "wait-failure-retry-" + UUID.randomUUID();
    List<String> errors = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-wait-failure-retry").build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-retry")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.waitForEvent("await", event, "k", String.class)
                          .timeout(SHORT)
                          .onFailure(
                              failing -> {
                                errors.add(failing.error());
                                if (errors.size() == 1) {
                                  throw new java.io.IOException("database down");
                                }
                                failing.resolve("\"late\"");
                              })
                          .receive())
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(errors)
          .as("the same stored error on both runs")
          .containsExactly(WAIT_TIMEOUT.formatted(event), WAIT_TIMEOUT.formatted(event));
      assertThat(count("id = '" + jobId + "' and attempts = 2")).isOne();
      assertThat(countSteps(jobId, "name = 'await' and failure_attempts = 2")).isOne();
    }
  }

  @Test
  void aFailureHandlerIsGivenUpAfterFailureHandlerAttempts() throws Exception {
    String event = "wait-failure-give-up-" + UUID.randomUUID();
    var runs = new AtomicInteger();
    try (Bikesched bikesched =
        testNode("node-wait-failure-give-up").failureHandlerAttempts(2).build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-give-up")
              .maxAttempts(1)
              .handler(
                  job ->
                      job.waitForEvent("await", event, "k", String.class)
                          .timeout(SHORT)
                          .onFailure(
                              failing -> {
                                runs.incrementAndGet();
                                throw new java.io.IOException("database down");
                              })
                          .receive())
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(runs).hasValue(2);
      assertThat(countSteps(jobId, "name = 'await' and state = 'FAILED'")).isOne();
    }
  }

  @Test
  void aPollTimeoutWithoutResolveFailsTheJob() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-poll-failure-fail").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-failure-fail")
              .maxAttempts(5)
              .handler(
                  job ->
                      job.newPoll("p", String.class, Duration.ofMillis(50))
                          .timeout(SHORT)
                          .onFailure(failing -> runs.incrementAndGet())
                          .run(ignored -> Poll.notReady()))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(runs).hasValue(1);
      assertThat(countSteps(jobId, "name = 'p' and state = 'FAILED'")).isOne();
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and last_error like 'Polling in step ''p'' timed out"
                      + " after PT0.2S (%'"))
          .isOne();
    }
  }

  @Test
  void aPollCheckFailingInTheLastAttemptRunsTheFailureHandler() throws Exception {
    List<String> errors = new CopyOnWriteArrayList<>();
    List<String> results = new CopyOnWriteArrayList<>();
    var checks = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-poll-failure-check").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-failure-check")
              .maxAttempts(2)
              .handler(
                  job ->
                      results.add(
                          job.newPoll("p", String.class, SHORT)
                              .onFailure(
                                  failing -> {
                                    errors.add(failing.error());
                                    failing.resolve("\"fallback\"");
                                  })
                              .run(
                                  ignored -> {
                                    throw new IllegalStateException(
                                        "check " + checks.incrementAndGet() + " failed");
                                  })))
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(errors)
          .as("only the check in the last attempt")
          .containsExactly("java.lang.IllegalStateException: check 2 failed");
      assertThat(results).containsExactly("\"fallback\"");
      assertThat(
              countSteps(
                  jobId, "name = 'p' and state = 'SUCCEEDED' and result = '\"fallback\"'::jsonb"))
          .isOne();
    }
  }

  @Test
  void aResolvedWaitReturnsItsFallbackWhenTheJobIsCompensated() throws Exception {
    String event = "wait-failure-compensate-" + UUID.randomUUID();
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-wait-failure-compensate").build()) {
      UUID jobId =
          bikesched
              .newJob("wait-failure-compensate")
              .maxAttempts(1)
              .handler(
                  job -> {
                    String payment =
                        job.waitForEvent("await", event, "k", String.class)
                            .timeout(SHORT)
                            .onFailure(failing -> failing.resolve("\"none\""))
                            .receive();
                    job.newStep("reserve", String.class)
                        .compensateWith((step, result) -> calls.add("release " + payment))
                        .run(ignored -> "\"r\"");
                    if (!job.isCompensating()) {
                      throw new IllegalStateException("fails permanently");
                    }
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(calls).containsExactly("release \"none\"");
    }
  }

  private static long countSteps(UUID jobId, String where) throws SQLException {
    return countRows("bikesched_steps", "job_id = '" + jobId + "' and " + where);
  }
}
