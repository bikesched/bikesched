// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.job.listener.JobAttemptOutcome;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;
import org.junit.jupiter.api.Test;

/** Failure handlers of job types, see {@link JobFailureHandler}. */
class JobFailureHandlerTest extends AbstractBikeschedTest {

  /** Records what a failure handler received, e.g. {@code "FAILED: java.lang...: boom"}. */
  private static String describe(FailedJob failed) {
    return failed.state() + ": " + failed.error();
  }

  @Test
  void runsOnceBeforeTheJobEndsFailed() throws Exception {
    String type = unique("failure-handler");
    List<String> handled = new CopyOnWriteArrayList<>();
    List<String> stateWhileHandling = new CopyOnWriteArrayList<>();
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-failure-handler").jobHistory(Duration.ofHours(1)).build()) {
      bikesched.registerJobFailureHandler(
          type,
          failed -> {
            stateWhileHandling.add(
                count(
                        "id = '"
                            + failed.id()
                            + "' and state = 'RUNNING' and failure_end_state = 'FAILED'")
                    + "");
            handled.add(failed.type() + " " + failed.payload() + " " + describe(failed));
          });
      UUID jobId =
          bikesched
              .newJob(type)
              .payload("\"order-1\"")
              .maxAttempts(2)
              .handler(
                  ignored -> {
                    throw new IllegalStateException("boom");
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(handled)
          .containsExactly(type + " \"order-1\" FAILED: java.lang.IllegalStateException: boom");
      assertThat(stateWhileHandling).as("claimed as FAILING").containsExactly("1");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 2 and failure_attempts = 1 and failure_end_state is null"
                      + " and finished_at is not null"
                      + " and last_error = 'java.lang.IllegalStateException: boom'"))
          .isOne();
      assertThat(bikesched.findJobHistory(jobId))
          .extracting(entry -> entry.state() + " " + entry.detail())
          .containsSubsequence(
              "FAILING java.lang.IllegalStateException: boom",
              "RUNNING failure handler run 1",
              "FAILED failure handler ran");
      assertThat(logs.messages())
          .contains(
              "Job "
                  + jobId
                  + " of type "
                  + type
                  + " failed permanently after 2 attempt(s), running its failure handler:"
                  + " java.lang.IllegalStateException: boom",
              "Job " + jobId + " of type " + type + " is FAILED: failure handler ran");
    }
  }

  @Test
  void runsAfterATimeoutAndAnExplicitFailure() throws Exception {
    String timedOut = unique("failure-handler-timeout");
    String failedExplicitly = unique("failure-handler-fail");
    List<String> handled = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-failure-handler-causes").build()) {
      bikesched.registerJobFailureHandler(timedOut, failed -> handled.add(describe(failed)));
      bikesched.registerJobFailureHandler(
          failedExplicitly, failed -> handled.add(describe(failed)));
      UUID timeoutId =
          bikesched
              .newJob(timedOut)
              .maxAttempts(1)
              .timeout(Duration.ofMillis(100))
              .handler(ignored -> Thread.sleep(5_000))
              .schedule();
      UUID failId =
          bikesched
              .newJob(failedExplicitly)
              .handler(job -> job.fail("invalid input").remainingAttempts(0))
              .schedule();

      awaitState(timeoutId, "FAILED");
      awaitState(failId, "FAILED");
      assertThat(handled)
          .containsExactlyInAnyOrder(
              "FAILED: org.bikesched.BikeschedException: Job timed out after PT0.1S",
              "FAILED: invalid input");
    }
  }

  @Test
  void runsAfterAnAbandonedAttempt() throws Exception {
    String type = unique("failure-handler-abandoned");
    var release = new AtomicBoolean();
    List<String> handled = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-failure-handler-abandoned")
            .hardTimeoutGrace(Duration.ofMillis(200))
            .build()) {
      bikesched.registerJobFailureHandler(type, failed -> handled.add(describe(failed)));
      UUID jobId =
          bikesched
              .newJob(type)
              .maxAttempts(1)
              .timeout(Duration.ofMillis(100))
              .handler(ignored -> ignoreInterruptsUntil(release))
              .schedule();

      awaitState(jobId, "FAILED");
      release.set(true);
      assertThat(handled)
          .containsExactly(
              "FAILED: org.bikesched.BikeschedException: Job timed out after PT0.1S and did not"
                  + " stop within the hard timeout grace of PT0.2S");
    }
  }

  @Test
  void doesNotRunForRetriedAttemptsCancelledJobsOrTypesWithoutHandler() throws Exception {
    String retried = unique("failure-handler-retried");
    String cancelled = unique("failure-handler-cancelled");
    String without = unique("failure-handler-without");
    var calls = new AtomicInteger();
    var attempts = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-failure-handler-not").build()) {
      bikesched.registerJobFailureHandler(retried, ignored -> calls.incrementAndGet());
      bikesched.registerJobFailureHandler(cancelled, ignored -> calls.incrementAndGet());
      UUID retriedId =
          bikesched
              .newJob(retried)
              .maxAttempts(3)
              .handler(
                  ignored -> {
                    if (attempts.incrementAndGet() < 3) {
                      throw new IllegalStateException("not yet");
                    }
                  })
              .schedule();
      UUID cancelledId =
          bikesched.newJob(cancelled).handler(job -> job.cancel("not needed")).schedule();
      UUID withoutId =
          bikesched
              .newJob(without)
              .maxAttempts(1)
              .handler(
                  ignored -> {
                    throw new IllegalStateException("boom");
                  })
              .schedule();

      awaitState(retriedId, "SUCCEEDED");
      awaitState(cancelledId, "CANCELLED");
      awaitState(withoutId, "FAILED");
      assertThat(calls).hasValue(0);
      assertThat(count("id = '" + withoutId + "' and failure_attempts = 0")).isOne();
    }
  }

  @Test
  void runsAfterTheCompensations() throws Exception {
    String failed = unique("failure-handler-compensated");
    String compensationFailed = unique("failure-handler-compensation-failed");
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-failure-handler-compensation").build()) {
      bikesched.registerJobFailureHandler(failed, job -> calls.add("handled " + describe(job)));
      bikesched.registerJobFailureHandler(
          compensationFailed, job -> calls.add("handled " + describe(job)));
      UUID failedId =
          bikesched
              .newJob(failed)
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith((ignored, ignored2) -> calls.add("compensated"))
                        .run(ignored -> "\"reserved\"");
                    throw new IllegalStateException("boom");
                  })
              .schedule();
      awaitState(failedId, "FAILED");
      assertThat(calls)
          .containsExactly("compensated", "handled FAILED: java.lang.IllegalStateException: boom");

      calls.clear();
      UUID compensationFailedId =
          bikesched
              .newJob(compensationFailed)
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith(
                            (ignored, ignored2) -> {
                              throw new IllegalStateException("cannot undo");
                            })
                        .run(ignored -> "\"reserved\"");
                    throw new IllegalStateException("boom");
                  })
              .schedule();
      awaitState(compensationFailedId, "COMPENSATION_FAILED");
      assertThat(calls)
          .containsExactly(
              "handled COMPENSATION_FAILED: Compensating step 'reserve' failed:"
                  + " java.lang.IllegalStateException: cannot undo");
      assertThat(
              count(
                  "id = '"
                      + compensationFailedId
                      + "' and compensation_end_state = 'FAILED' and failure_end_state is null"))
          .isOne();
    }
  }

  @Test
  void aThrowingHandlerRunsAgainAndIsGivenUpAfterFailureHandlerAttempts() throws Exception {
    String recovers = unique("failure-handler-recovers");
    String givesUp = unique("failure-handler-gives-up");
    var recoverCalls = new AtomicInteger();
    var giveUpCalls = new AtomicInteger();
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-failure-handler-retry")
                .failureHandlerAttempts(2)
                .jobHistory(Duration.ofHours(1))
                .build()) {
      bikesched.registerJobFailureHandler(
          recovers,
          ignored -> {
            if (recoverCalls.incrementAndGet() == 1) {
              throw new java.io.IOException("dead letters down");
            }
          });
      bikesched.registerJobFailureHandler(
          givesUp,
          ignored -> {
            giveUpCalls.incrementAndGet();
            throw new java.io.IOException("dead letters down");
          });
      UUID recoversId = failingJob(bikesched, recovers);
      UUID givesUpId = failingJob(bikesched, givesUp);

      awaitState(recoversId, "FAILED");
      awaitState(givesUpId, "FAILED");
      assertThat(recoverCalls).hasValue(2);
      assertThat(giveUpCalls).as("given up after 2 runs").hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + givesUpId
                      + "' and failure_attempts = 2"
                      + " and last_error = 'java.lang.IllegalStateException: boom'"))
          .as("the job keeps its own error")
          .isOne();
      assertThat(bikesched.findJobHistory(givesUpId))
          .extracting(entry -> entry.state() + " " + entry.detail())
          .contains(
              "FAILED failure handler gave up after 2 run(s): java.io.IOException: dead letters"
                  + " down");
      assertThat(logs.messages())
          .contains(
              "Failure handler of job "
                  + givesUpId
                  + " of type "
                  + givesUp
                  + " gave up after 2 run(s), the job ends FAILED");
    }
  }

  @Test
  void aNodeWithoutTheFailureHandlerEndsAFailingJob() throws Exception {
    String type = unique("failure-handler-removed");
    UUID jobId = UUID.randomUUID();
    execute(
        "insert into bikesched_jobs (id, type, state, failure_end_state, attempts, last_error) values ('"
            + jobId
            + "', '"
            + type
            + "', 'FAILING', 'FAILED', 3, 'boom')");
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched = testNode("node-failure-handler-removed").build()) {
      bikesched.registerJobHandler(type, ignored -> {});

      awaitState(jobId, "FAILED");
      assertThat(count("id = '" + jobId + "' and attempts = 3 and failure_attempts = 1")).isOne();
      assertThat(logs.messages())
          .anyMatch(
              message ->
                  message.startsWith("No failure handler registered for job type " + type)
                      && message.endsWith("job " + jobId + " ends FAILED without it"));
    }
  }

  @Test
  void aParentReceivesTheOutcomeOfAChildOnlyAfterItsFailureHandlerRan() throws Exception {
    String child = unique("failure-handler-child");
    String parent = unique("failure-handler-parent");
    List<String> calls = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-failure-handler-child").build()) {
      bikesched.registerJobHandler(
          child,
          ignored -> {
            throw new IllegalStateException("boom");
          });
      bikesched.registerJobFailureHandler(
          child,
          ignored -> {
            Thread.sleep(200);
            calls.add("child handled");
          });
      UUID parentId =
          bikesched
              .newJob(parent)
              .handler(
                  job -> {
                    UUID childId = job.newJob(child).maxAttempts(1).schedule();
                    JobResult<String> result = job.waitForJob(childId, String.class);
                    calls.add("parent got " + result.getClass().getSimpleName());
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(calls).containsExactly("child handled", "parent got Failed");
    }
  }

  @Test
  void theNextOccurrenceIsScheduledOnlyAfterTheFailureHandlerRan() throws Exception {
    String type = unique("failure-handler-recurring");
    var release = new CountDownLatch(1);
    var handling = new CountDownLatch(1);
    try (Bikesched bikesched = testNode("node-failure-handler-recurring").build()) {
      bikesched.registerJobFailureHandler(
          type,
          ignored -> {
            handling.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
          });
      bikesched
          .newJob(type)
          .maxAttempts(1)
          .handler(
              ignored -> {
                throw new IllegalStateException("boom");
              })
          .schedule(Recurrence.every(Duration.ofHours(1)), Duration.ZERO);

      assertThat(handling.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(count("type = '" + type + "'")).as("no next occurrence yet").isOne();
      release.countDown();

      await()
          .untilAsserted(
              () ->
                  assertThat(
                          count(
                              "type = '"
                                  + type
                                  + "' and state = 'SCHEDULED' and scheduled_at > now()"))
                      .isOne());
      assertThat(count("type = '" + type + "' and state = 'FAILED'")).isOne();
      bikesched.removeRecurringJob(type);
    } finally {
      execute("delete from bikesched_jobs where type = '" + type + "'");
    }
  }

  @Test
  void typedAndClassBasedFailureHandlersGetTheMappedPayload() throws Exception {
    JobType<String> typed = JobType.of(unique("failure-handler-typed"), String.class);
    JobType<String> classBased = JobType.of(unique("failure-handler-class"), String.class);
    List<String> handled = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-failure-handler-typed").build()) {
      bikesched.registerJobFailureHandler(
          typed, (failed, payload) -> handled.add("typed " + payload));
      var handler =
          new JobTypeHandlerWithFailureHandler(
              classBased, payload -> handled.add("class " + payload));
      bikesched.registerJobHandler(handler).registerJobFailureHandler(handler);
      UUID withPayload =
          bikesched
              .newJob(typed)
              .payload("\"order-1\"")
              .maxAttempts(1)
              .handler(
                  (ignored, ignored2) -> {
                    throw new IllegalStateException("boom");
                  })
              .schedule();
      UUID withoutPayload = bikesched.newJob(typed).maxAttempts(1).schedule();
      UUID classBasedId =
          bikesched.newJob(classBased).payload("\"order-2\"").maxAttempts(1).schedule();

      awaitState(withPayload, "FAILED");
      awaitState(withoutPayload, "FAILED");
      awaitState(classBasedId, "FAILED");
      assertThat(handled)
          .containsExactlyInAnyOrder("typed \"order-1\"", "typed null", "class \"order-2\"");
    }
  }

  /** A class-based job handler that always fails, with its failure handler. */
  private record JobTypeHandlerWithFailureHandler(
      JobType<String> type, java.util.function.Consumer<String> onFailure)
      implements JobTypeHandler<String>, JobTypeFailureHandler<String> {

    @Override
    public void handle(Job job, String payload) {
      throw new IllegalStateException("boom");
    }

    @Override
    public void handle(FailedJob job, String payload) {
      onFailure.accept(payload);
    }
  }

  @Test
  void theBuilderRegistersTheFailureHandlerAndKeepsAnExistingOne() throws Exception {
    String type = unique("failure-handler-builder");
    List<String> handled = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-failure-handler-builder").build()) {
      UUID first =
          bikesched
              .newJob(type)
              .maxAttempts(1)
              .handler(
                  ignored -> {
                    throw new IllegalStateException("boom");
                  })
              .failureHandler(failed -> handled.add("first " + failed.id()))
              .schedule();
      UUID second =
          bikesched
              .newJob(type)
              .maxAttempts(1)
              .failureHandler((failed, ignored) -> handled.add("second " + failed.id()))
              .schedule();

      awaitState(first, "FAILED");
      awaitState(second, "FAILED");
      assertThat(handled).containsExactlyInAnyOrder("first " + first, "first " + second);
      assertThatThrownBy(() -> bikesched.registerJobFailureHandler(type, ignored -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("A failure handler for job type '" + type + "' is already registered");
    }
  }

  @Test
  void theListenerSeesTheFailingAttemptAndTheFailureHandlerRuns() throws Exception {
    String type = unique("failure-handler-listener");
    List<String> calls = new CopyOnWriteArrayList<>();
    var listener =
        new JobListener() {
          @Override
          public void onJobClaimed(JobClaim claim) {
            calls.add((claim.failing() ? "claimed failing " : "claimed ") + claim.attempt());
          }

          @Override
          public void onAttemptEnded(JobAttemptResult result) {
            calls.add("ended " + result.claim().attempt() + " " + result.outcome());
          }
        };
    var runs = new AtomicInteger();
    try (Bikesched bikesched =
        testNode("node-failure-handler-listener").jobListener(listener).build()) {
      bikesched.registerJobFailureHandler(
          type,
          ignored -> {
            if (runs.incrementAndGet() == 1) {
              throw new java.io.IOException("dead letters down");
            }
          });
      UUID jobId = failingJob(bikesched, type);

      awaitState(jobId, "FAILED");
      // The next claim can be reported before the previous attempt's end: claims and ends are each
      // in order.
      await()
          .untilAsserted(
              () -> {
                assertThat(calls.stream().filter(c -> c.startsWith("claimed ")))
                    .containsExactly("claimed 1", "claimed failing 1", "claimed failing 2");
                assertThat(calls.stream().filter(c -> c.startsWith("ended ")))
                    .containsExactly(
                        "ended 1 " + JobAttemptOutcome.FAILING,
                        "ended 1 " + JobAttemptOutcome.FAILURE_HANDLER_RETRYING,
                        "ended 2 " + JobAttemptOutcome.FAILED);
              });
    }
  }

  /** Schedules a job of the type that fails in its only attempt. */
  private static UUID failingJob(Bikesched bikesched, String type) {
    return bikesched
        .newJob(type)
        .maxAttempts(1)
        .handler(
            ignored -> {
              throw new IllegalStateException("boom");
            })
        .schedule();
  }

  /** Waits like non-interruptible code would: swallows every interrupt. */
  private static void ignoreInterruptsUntil(AtomicBoolean release) {
    while (!release.get()) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ignored) {
        // ignored on purpose: the handler does not react to its timeout
      }
    }
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
