// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;

/**
 * Compensating steps ({@link StepBuilder#compensateWith(CompensationHandler)}) when a job is
 * cancelled or fails permanently, and cancelling jobs via {@link Bikesched#cancel(UUID)}.
 */
class CompensationTest extends AbstractBikeschedTest {

  @Test
  void cancellingCompensatesCompletedStepsInReverseOrder() throws Exception {
    try (Bikesched bikesched = node("node-compensate-cancel")) {
      List<String> calls = new CopyOnWriteArrayList<>();
      UUID jobId =
          bikesched
              .newJob("compensate-cancel")
              .handler(
                  job -> {
                    String a =
                        job.newStep("a", String.class)
                            .compensateWith((_, result) -> calls.add("undo " + result))
                            .run(_ -> "1");
                    job.step("no-compensation", String.class, _ -> "0");
                    String b =
                        job.newStep("b", String.class)
                            .compensateWith((_, result) -> calls.add("undo " + result))
                            .run(_ -> "2");
                    calls.add((job.isCompensating() ? "replayed " : "did ") + a + ", " + b);
                    if (!job.isCompensating()) {
                      job.cancel("no longer needed");
                    }
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(calls).containsExactly("did 1, 2", "replayed 1, 2", "undo 2", "undo 1");
      assertThat(count("id = '" + jobId + "' and last_error = 'no longer needed' and attempts = 1"))
          .isEqualTo(1);
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "' and state = 'COMPENSATED'"))
          .isEqualTo(2);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + jobId + "' and name = 'no-compensation' and state = 'SUCCEEDED'"))
          .isEqualTo(1);
    }
  }

  @Test
  void jobsWithoutCompensationsEndRightAway() throws Exception {
    try (Bikesched bikesched = node("node-compensate-optional")) {
      var runs = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("compensate-optional")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    job.step("a", String.class, _ -> "1");
                    job.newStep("b", String.class).run(_ -> "2"); // built, but no compensation
                    job.cancel();
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(runs).as("not replayed").hasValue(1);
      assertThat(count("id = '" + jobId + "' and compensation_attempts = 0")).isEqualTo(1);
    }
  }

  @Test
  void permanentFailureCompensatesUntilTheFailedStep() throws Exception {
    try (Bikesched bikesched = node("node-compensate-fail")) {
      List<String> compensated = new CopyOnWriteArrayList<>();
      List<Step> compensatedSteps = new CopyOnWriteArrayList<>();
      UUID jobId =
          bikesched
              .newJob("compensate-fail")
              .maxAttempts(2)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith(
                            (step, result) -> {
                              compensatedSteps.add(step);
                              compensated.add(result);
                            })
                        .run(_ -> "42");
                    job.newStep("charge", String.class)
                        .compensateWith((_, result) -> compensated.add(result))
                        .run(
                            _ -> {
                              throw new IllegalStateException("card declined");
                            });
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(compensated).as("the failed step is not compensated").containsExactly("42");
      assertThat(compensatedSteps)
          .as("the compensation receives the step with the attempts it took")
          .containsExactly(new Step(jobId, "reserve", 1));
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 2 and compensation_attempts = 1"
                      + " and last_error = 'java.lang.IllegalStateException: card declined'"))
          .isEqualTo(1);
    }
  }

  @Test
  void compensationCannotRunSteps() throws Exception {
    try (Bikesched bikesched = node("node-compensate-nested")) {
      List<String> rejected = new CopyOnWriteArrayList<>();
      UUID jobId =
          bikesched
              .newJob("compensate-nested")
              .handler(
                  job -> {
                    job.newStep("a", String.class)
                        .compensateWith(
                            (_, _) -> {
                              try {
                                job.step("undo", String.class, _ -> "\"undo\"");
                              } catch (IllegalStateException e) {
                                rejected.add(e.getMessage());
                              }
                            })
                        .run(_ -> "1");
                    job.cancel();
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(rejected)
          .containsExactly(
              "Job "
                  + jobId
                  + " cannot run step 'undo' while the compensation of step 'a' runs:"
                  + " steps and event waits cannot be nested");
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "' and name = 'undo'"))
          .isZero();
    }
  }

  @Test
  void failedCompensationIsRetriedThenCompensationFailed() throws Exception {
    try (Bikesched bikesched = node("node-compensate-retry")) {
      var attempts = new AtomicInteger();
      UUID retried =
          bikesched
              .newJob("compensate-retry")
              .handler(
                  job -> {
                    job.newStep("a", String.class)
                        .compensateWith(
                            (_, _) -> {
                              if (attempts.incrementAndGet() == 1) {
                                throw new IllegalStateException("provider down");
                              }
                            })
                        .run(_ -> "1");
                    job.cancel();
                  })
              .schedule();
      awaitState(retried, "CANCELLED");
      assertThat(attempts).hasValue(2);
      assertThat(count("id = '" + retried + "' and compensation_attempts = 2")).isEqualTo(1);

      UUID failing =
          bikesched
              .newJob("compensate-retry-failing")
              .maxAttempts(2)
              .handler(
                  job -> {
                    job.newStep("a", String.class)
                        .compensateWith(
                            (_, _) -> {
                              throw new IllegalStateException("provider down");
                            })
                        .run(_ -> "1");
                    job.cancel();
                  })
              .schedule();
      awaitState(failing, "COMPENSATION_FAILED");
      assertThat(
              count(
                  "id = '"
                      + failing
                      + "' and compensation_attempts = 2 and last_error ="
                      + " 'Compensating step ''a'' failed: java.lang.IllegalStateException:"
                      + " provider down'"))
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + failing
                      + "' and state = 'SUCCEEDED'"
                      + " and last_error = 'java.lang.IllegalStateException: provider down'"))
          .isEqualTo(1);
    }
  }

  @Test
  void cancellingAWaitingJobCancelsItsStepAndCompensates() throws Exception {
    try (Bikesched bikesched = node("node-compensate-waiting")) {
      List<String> compensated = new CopyOnWriteArrayList<>();
      var handlerRuns = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("compensate-waiting")
              .handler(
                  job -> {
                    handlerRuns.incrementAndGet();
                    job.newStep("reserve", String.class)
                        .compensateWith((_, result) -> compensated.add(result))
                        .run(_ -> "42");
                    job.waitForEvent("await-driver", "driver-accepted", job.id().toString())
                        .receive(String.class);
                    job.step("never", String.class, _ -> "0");
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      assertThat(bikesched.cancel(jobId, "customer withdrew")).isTrue();

      awaitState(jobId, "CANCELLED");
      assertThat(compensated).containsExactly("42");
      assertThat(handlerRuns).as("first run, then the compensation replay").hasValue(2);
      assertThat(count("id = '" + jobId + "' and last_error = 'customer withdrew'")).isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '" + jobId + "' and name = 'await-driver' and state = 'CANCELLED'"))
          .as("the waiting step is cancelled, not deleted")
          .isEqualTo(1);
      assertThat(bikesched.newEvent("driver-accepted", jobId.toString()).publish())
          .as("a cancelled step no longer receives events")
          .isEmpty();
      assertThat(bikesched.cancel(jobId)).as("already cancelled").isFalse();
    }
  }

  @Test
  void cancellingAScheduledJobDoesNotRunItsHandler() throws Exception {
    try (Bikesched bikesched = node("node-cancel-scheduled")) {
      var runs = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("cancel-scheduled")
              .handler(_ -> runs.incrementAndGet())
              .schedule(Duration.ofHours(1));

      assertThat(bikesched.cancel(jobId)).isTrue();

      awaitState(jobId, "CANCELLED");
      assertThat(runs).hasValue(0);
      assertThat(bikesched.cancel(UUID.randomUUID())).as("unknown job").isFalse();
    }
  }

  @Test
  void cancellingARunningJobCancelsItAfterItsAttempt() throws Exception {
    try (Bikesched bikesched = node("node-cancel-running")) {
      var started = new java.util.concurrent.CountDownLatch(1);
      var proceed = new java.util.concurrent.CountDownLatch(1);
      List<String> compensated = new CopyOnWriteArrayList<>();
      UUID jobId =
          bikesched
              .newJob("cancel-running")
              .handler(
                  job -> {
                    job.newStep("a", String.class)
                        .compensateWith((_, result) -> compensated.add(result))
                        .run(_ -> "1");
                    if (!job.isCompensating()) {
                      started.countDown();
                      proceed.await();
                      job.fail("retry me");
                    }
                  })
              .schedule();
      assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      assertThat(bikesched.cancel(jobId)).isTrue();
      proceed.countDown();

      awaitState(jobId, "CANCELLED");
      assertThat(compensated).containsExactly("1");
      assertThat(count("id = '" + jobId + "' and attempts = 1")).as("not retried").isEqualTo(1);
    }
  }

  private static Bikesched node(String name) {
    return testNode(name).build();
  }
}
