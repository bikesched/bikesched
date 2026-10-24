// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.job.listener.JobAttemptOutcome;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobListener;
import org.junit.jupiter.api.Test;

/**
 * {@link Job#postpone(Duration)}: ends the attempt and runs the job again after the delay, without
 * using up an attempt or recording a failure; in a step, the step keeps no result and runs again.
 */
class PostponeTest extends AbstractBikeschedTest {

  private static final Duration SHORT = Duration.ofMillis(300);

  @Test
  void postponedOutsideAStepRunsAgainAfterTheDelay() throws Exception {
    var runs = new CopyOnWriteArrayList<Long>();
    var attempts = new CopyOnWriteArrayList<Integer>();
    try (Bikesched bikesched = node("node-postpone")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone"))
              .maxAttempts(1)
              .handler(
                  job -> {
                    runs.add(System.nanoTime());
                    attempts.add(job.attempts());
                    if (runs.size() == 1) {
                      job.postpone(SHORT);
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(attempts).as("the attempt is given back").containsExactly(1, 1);
      assertThat(Duration.ofNanos(runs.get(1) - runs.get(0))).isGreaterThanOrEqualTo(SHORT);
      assertThat(count("id = '" + jobId + "' and attempts = 1 and last_error is null")).isOne();
      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly("SCHEDULED", "RUNNING", "SCHEDULED", "RUNNING", "SUCCEEDED");
      assertThat(history).extracting(JobHistoryEntry::attempt).containsExactly(0, 1, 0, 1, 1);
      assertThat(history.get(2).detail()).startsWith("postponed until ");
    }
  }

  @Test
  void postponedInAStepRunsTheStepAgainAndSkipsTheCompletedOnes() throws Exception {
    var reserved = new AtomicInteger();
    var stepAttempts = new CopyOnWriteArrayList<Integer>();
    try (Bikesched bikesched = node("node-postpone-step")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-step"))
              .handler(
                  job -> {
                    job.step(
                        "reserve",
                        String.class,
                        ignored -> "\"r-" + reserved.incrementAndGet() + "\"");
                    job.step(
                        "charge",
                        String.class,
                        step -> {
                          stepAttempts.add(step.attempts());
                          if (stepAttempts.size() == 1) {
                            job.postpone(SHORT);
                          }
                          return "\"c-1\"";
                        });
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(reserved).as("the completed step is skipped").hasValue(1);
      assertThat(stepAttempts).as("the step's attempt is given back").containsExactly(1, 1);
      assertThat(stepResult(jobId, "charge")).isEqualTo("\"c-1\"");
      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly(
              "SCHEDULED",
              "RUNNING",
              "reserve:RUNNING",
              "reserve:SUCCEEDED",
              "charge:RUNNING",
              "charge:POSTPONED",
              "SCHEDULED",
              "RUNNING",
              "charge:RUNNING",
              "charge:SUCCEEDED",
              "SUCCEEDED");
      assertThat(history.get(5).detail()).startsWith("until ");
      assertThat(history.get(6).detail()).startsWith("postponed in step 'charge' until ");
      assertThat(history.get(8).detail()).as("no 'step attempt 2'").isNull();
    }
  }

  @Test
  void postponingRepeatedlyNeverFailsTheJob() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = node("node-postpone-repeated")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-repeated"))
              .maxAttempts(1)
              .handler(
                  job -> {
                    if (runs.incrementAndGet() <= 3) {
                      job.postpone(Duration.ofMillis(50));
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(runs).hasValue(4);
    }
  }

  @Test
  void handlerCatchingThePostponementInAStepIsStillPostponed() throws Exception {
    var runs = new AtomicInteger();
    try (Bikesched bikesched = node("node-postpone-caught")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-caught"))
              .handler(
                  job ->
                      job.step(
                          "charge",
                          String.class,
                          step -> {
                            if (runs.incrementAndGet() == 1) {
                              try {
                                job.postpone(SHORT);
                              } catch (Throwable ignored) {
                                // swallowed by mistake
                              }
                              return "\"too early\"";
                            }
                            return "\"charged\"";
                          }))
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(runs).hasValue(2);
      assertThat(stepResult(jobId, "charge")).isEqualTo("\"charged\"");
    }
  }

  @Test
  void cancellationRequestedMeanwhileAppliesRightAway() throws Exception {
    var running = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (Bikesched bikesched = node("node-postpone-cancel")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-cancel"))
              .handler(
                  job -> {
                    running.countDown();
                    release.await();
                    job.postpone(Duration.ofHours(1));
                  })
              .schedule();
      assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(bikesched.cancel(jobId, "no longer needed")).isTrue();
      release.countDown();

      awaitState(jobId, "CANCELLED");
    } finally {
      release.countDown();
    }
  }

  @Test
  void rejectedInACompensationAStepFailureHandlerAndAPollCheck() throws Exception {
    var rejected = new CopyOnWriteArrayList<String>();
    try (Bikesched bikesched = node("node-postpone-rejected")) {
      UUID compensated =
          bikesched
              .newJob(unique("postpone-compensation"))
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith(
                            (ignored, ignored2) -> rejected.add(attempt(() -> job.postpone(SHORT))))
                        .run(ignored -> "\"r-1\"");
                    job.cancel("compensate");
                  })
              .schedule();
      UUID failing =
          bikesched
              .newJob(unique("postpone-step-failure"))
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newStep("charge", String.class)
                          .onFailure(ignored -> rejected.add(attempt(() -> job.postpone(SHORT))))
                          .run(
                              ignored -> {
                                throw new IllegalStateException("declined");
                              }))
              .schedule();
      UUID polling =
          bikesched
              .newJob(unique("postpone-poll"))
              .maxAttempts(1)
              .handler(
                  job ->
                      job.newPoll("await", String.class, SHORT)
                          .run(
                              ignored -> {
                                rejected.add(attempt(() -> job.postpone(SHORT)));
                                return Poll.ready("\"done\"");
                              }))
              .schedule();
      awaitState(compensated, "CANCELLED");
      awaitState(failing, "FAILED");
      awaitState(polling, "SUCCEEDED");

      assertThat(rejected)
          .hasSize(3)
          .allSatisfy(
              message ->
                  assertThat(message)
                      .startsWith("IllegalStateException: Job ")
                      .contains("cannot postpone while"));
    }
  }

  @Test
  void ignoredWhileReplayingForCompensation() throws Exception {
    var compensated = new CompletableFuture<String>();
    try (Bikesched bikesched = node("node-postpone-replay")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-replay"))
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith((ignored, reservation) -> compensated.complete(reservation))
                        .run(ignored -> "\"r-1\"");
                    job.postpone(Duration.ofHours(1));
                  })
              .schedule();
      await()
          .until(
              () ->
                  count(
                          "id = '"
                              + jobId
                              + "' and state = 'SCHEDULED' and scheduled_at > now() + interval '30"
                              + " minutes'")
                      == 1);

      assertThat(bikesched.cancel(jobId)).isTrue();

      assertThat(compensated.get(10, TimeUnit.SECONDS)).isEqualTo("\"r-1\"");
      awaitState(jobId, "CANCELLED");
    }
  }

  @Test
  void nonPositiveDelaysAreRejected() throws Exception {
    var rejected = new CopyOnWriteArrayList<String>();
    try (Bikesched bikesched = node("node-postpone-delay")) {
      UUID jobId =
          bikesched
              .newJob(unique("postpone-delay"))
              .handler(
                  job -> {
                    rejected.add(attempt(() -> job.postpone(Duration.ZERO)));
                    rejected.add(attempt(() -> job.postpone(Duration.ofSeconds(-1))));
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(rejected)
          .containsExactly(
              "IllegalArgumentException: delay must be positive: PT0S",
              "IllegalArgumentException: delay must be positive: PT-1S");
    }
  }

  @Test
  void listenerReportsThePostponedAttempt() throws Exception {
    var outcomes = new CopyOnWriteArrayList<JobAttemptOutcome>();
    String type = unique("postpone-listener");
    JobListener listener =
        new JobListener() {
          @Override
          public void onAttemptEnded(JobAttemptResult result) {
            if (result.claim().jobName().equals(type)) {
              outcomes.add(result.outcome());
            }
          }
        };
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-postpone-listener").jobListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job -> {
                    if (runs.incrementAndGet() == 1) {
                      job.postpone(Duration.ofMillis(50));
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      await()
          .untilAsserted(
              () ->
                  assertThat(outcomes)
                      .containsExactly(JobAttemptOutcome.POSTPONED, JobAttemptOutcome.SUCCEEDED));
    }
  }

  /** Runs the call and returns the exception it threw as {@code SimpleName: message}. */
  private static String attempt(Runnable call) {
    try {
      call.run();
      return "returned";
    } catch (RuntimeException e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  private static String stepResult(UUID jobId, String step) throws Exception {
    try (var connection = dataSource.getConnection();
        var select =
            connection.prepareStatement(
                "select result::text from bikesched_steps where job_id = ? and name = ?")) {
      select.setObject(1, jobId);
      select.setString(2, step);
      try (var rs = select.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /** The changes as {@code STATE} (job) or {@code step:STATE} (step). */
  private static List<String> changes(List<JobHistoryEntry> history) {
    return history.stream()
        .map(e -> e.isStepEntry() ? e.stepName() + ":" + e.state() : e.state())
        .toList();
  }

  /** A polling node recording the job history. */
  private static Bikesched node(String name) {
    return testNode(name).jobHistory(Duration.ofDays(1)).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
