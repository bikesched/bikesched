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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Child jobs and job results: {@link Job#complete(Object)}, {@link Job#newJob(String)} and {@link
 * Job#waitForJob(UUID, Class)}.
 */
class ChildJobTest extends AbstractBikeschedTest {

  /** Deletes the jobs left behind (waiting children, future jobs). */
  @AfterEach
  void deleteJobs() throws Exception {
    execute("delete from bikesched_jobs where type like 'child-%'");
  }

  // ---- Job results ----

  @Test
  void theResultIsStoredWhenTheJobSucceeds() throws Exception {
    try (Bikesched bikesched =
        testNode("node-child-result").jobHistory(Duration.ofHours(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("child-result")
              .handler(
                  job -> {
                    job.complete("\"first\"");
                    job.complete("{\"secret\": \"s3cr3t\"}"); // the last one wins
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      JobInfo info = bikesched.findJob(jobId).orElseThrow();
      assertThat(info.result(String.class)).contains("{\"secret\": \"s3cr3t\"}");
      assertThat(info.parentJobId()).isEmpty();
      assertThat(bikesched.findJobHistory(jobId))
          .anyMatch(
              entry ->
                  entry.stepName() == null
                      && entry.state().equals("SUCCEEDED")
                      && "succeeded with result".equals(entry.detail()))
          .noneMatch(entry -> entry.detail() != null && entry.detail().contains("s3cr3t"));
    }
  }

  @Test
  void aJobWithoutResultHasNone() throws Exception {
    try (Bikesched bikesched = testNode("node-child-no-result").build()) {
      UUID jobId = bikesched.newJob("child-no-result").handler(() -> {}).schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.findJob(jobId).orElseThrow().result(String.class)).isEmpty();
    }
  }

  @Test
  void failingCancellingOrThrowingAfterCompleteDiscardsTheResult() throws Exception {
    var attempts = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-child-result-discarded").build()) {
      UUID failed =
          bikesched
              .newJob("child-result-failed")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.complete("\"r\"");
                    job.fail("no");
                  })
              .schedule();
      UUID cancelled =
          bikesched
              .newJob("child-result-cancelled")
              .handler(
                  job -> {
                    job.complete("\"r\"");
                    job.cancel("no");
                  })
              .schedule();
      UUID thrown =
          bikesched
              .newJob("child-result-thrown")
              .handler(
                  job -> {
                    if (attempts.incrementAndGet() == 1) {
                      job.complete("\"r\"");
                      throw new IllegalStateException("boom");
                    }
                  })
              .schedule();

      awaitState(failed, "FAILED");
      awaitState(cancelled, "CANCELLED");
      awaitState(thrown, "SUCCEEDED");
      assertThat(count("id in ('" + failed + "', '" + cancelled + "', '" + thrown + "')"))
          .isEqualTo(3);
      assertThat(
              count(
                  "id in ('"
                      + failed
                      + "', '"
                      + cancelled
                      + "', '"
                      + thrown
                      + "') and result is not null"))
          .as("no result: the second attempt of the thrown job set none")
          .isZero();
    }
  }

  // ---- Starting and waiting for children ----

  @Test
  void aParentWaitsForItsChildAndReceivesItsResult() throws Exception {
    List<JobResult<String>> outcomes = new CopyOnWriteArrayList<>();
    var childIds = new CopyOnWriteArrayList<UUID>();
    var logs = LogCapture.of(Job.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-child-basic").jobHistory(Duration.ofHours(1)).build()) {
      // The child ends only once its parent waits for it, so the test always takes the path it
      // asserts: the parent suspends WAITING, the child's end resumes it. (A child ending before
      // the suspension is covered by aChildEndingWhileTheParentSuspendsMakesTheParentDue.)
      bikesched.registerJobHandler(
          "child-basic-child",
          job -> {
            UUID parent = bikesched.findJob(job.id()).orElseThrow().parentJobId().orElseThrow();
            await().until(() -> count("id = '" + parent + "' and state = 'WAITING'") == 1);
            job.complete("{\"echo\": " + job.payload() + "}");
          });
      UUID parentId =
          bikesched
              .newJob("child-basic-parent")
              .handler(
                  job -> {
                    UUID child = job.newJob("child-basic-child").payload("\"hi\"").schedule();
                    childIds.add(child);
                    outcomes.add(job.waitForJob(child, String.class));
                    job.complete("\"done\"");
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      UUID childId = childIds.getFirst();
      assertThat(childIds).as("one child, the same on every run").containsOnly(childId);
      assertThat(outcomes)
          .containsExactly(new JobResult.Succeeded<>(childId, "{\"echo\": \"hi\"}"));
      assertThat(count("parent_job_id = '" + parentId + "'")).isEqualTo(1);
      JobInfo child = bikesched.findJob(childId).orElseThrow();
      assertThat(child.parentJobId()).contains(parentId);
      assertThat(child.result(String.class)).contains("{\"echo\": \"hi\"}");
      assertThat(bikesched.findJob(parentId).orElseThrow().result(String.class))
          .contains("\"done\"");
      assertThat(count("id = '" + parentId + "' and attempts = 1"))
          .as("waiting gives the attempt back")
          .isEqualTo(1);
      assertThat(
              countSteps(
                  parentId,
                  "name = 'start child-basic-child' and kind = 'CHILD_JOB' and state = 'SUCCEEDED'"
                      + " and child_state = 'SUCCEEDED' and child_job_id = '"
                      + childId
                      + "'"))
          .isEqualTo(1);

      assertThat(bikesched.findJobHistory(parentId))
          .extracting(ChildJobTest::describe)
          .containsSubsequence(
              "start child-basic-child WAITING started job " + childId,
              "job WAITING suspended at step 'start child-basic-child', waiting for job " + childId,
              "start child-basic-child SUCCEEDED job " + childId + " ended SUCCEEDED",
              "job SCHEDULED resumed by job " + childId,
              "job SUCCEEDED succeeded with result");
      assertThat(bikesched.findJobHistory(childId))
          .extracting(ChildJobTest::describe)
          .first()
          .asString()
          .startsWith(
              "job SCHEDULED started by job "
                  + parentId
                  + " step 'start child-basic-child', due at ");
      assertThat(logs.messages())
          .contains(
              "Step start child-basic-child of job "
                  + parentId
                  + " started child job "
                  + childId
                  + " of type child-basic-child",
              "Step start child-basic-child of job "
                  + parentId
                  + " waiting for job "
                  + childId
                  + ", suspending the job",
              "Step start child-basic-child of job "
                  + parentId
                  + " received the outcome of job "
                  + childId
                  + ": SUCCEEDED");
    }
  }

  @Test
  void theChildIsStartedOnceAcrossRetriesAndSleeps() throws Exception {
    var childIds = new CopyOnWriteArrayList<UUID>();
    var runs = new AtomicInteger();
    try (Bikesched bikesched = testNode("node-child-once").build()) {
      bikesched.registerJobHandler("child-once-child", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-once-parent")
              .handler(
                  job -> {
                    childIds.add(job.newJob("child-once-child").stepName("child").schedule());
                    job.sleep("nap", Duration.ofMillis(100));
                    if (runs.incrementAndGet() == 1) {
                      throw new IllegalStateException("retry me");
                    }
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(childIds).hasSizeGreaterThanOrEqualTo(3).containsOnly(childIds.getFirst());
      assertThat(count("parent_job_id = '" + parentId + "'")).isEqualTo(1);
    }
  }

  @Test
  void aChildIsNotStoredIfItsStepFails() throws Exception {
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-atomic").build()) {
      bikesched.registerJobHandler("child-atomic-child", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-atomic-parent")
              .maxAttempts(1)
              .handler(
                  job -> {
                    try {
                      job.newJob("child-atomic-child").payload("not json").schedule();
                    } catch (RuntimeException e) {
                      failure.set(e);
                      throw e;
                    }
                  })
              .schedule();

      awaitState(parentId, "FAILED");
      assertThat(failure.get()).hasMessageContaining("Starting a child job");
      assertThat(count("parent_job_id = '" + parentId + "'")).isZero();
      assertThat(countSteps(parentId, "true")).isZero();
    }
  }

  @Test
  void eachWayAChildEndsReachesItsParent() throws Exception {
    List<JobResult<String>> outcomes = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-child-outcomes").build()) {
      bikesched.registerJobHandler("child-outcome-ok", job -> job.complete("\"ok\""));
      bikesched.registerJobHandler(
          "child-outcome-failed",
          job -> {
            throw new IllegalStateException("broken");
          });
      bikesched.registerJobHandler("child-outcome-cancelled", job -> job.cancel("not needed"));
      bikesched.registerJobHandler(
          "child-outcome-compensation-failed",
          job -> {
            job.newStep("reserve", String.class)
                .compensateWith(
                    (ignored, ignored2) -> {
                      throw new IllegalStateException("cannot undo");
                    })
                .run(ignored -> "\"reserved\"");
            job.fail("then failed");
          });
      UUID parentId =
          bikesched
              .newJob("child-outcome-parent")
              .handler(
                  job -> {
                    UUID ok = job.newJob("child-outcome-ok").schedule();
                    UUID failed = job.newJob("child-outcome-failed").maxAttempts(1).schedule();
                    UUID cancelled = job.newJob("child-outcome-cancelled").schedule();
                    UUID compensationFailed =
                        job.newJob("child-outcome-compensation-failed").maxAttempts(1).schedule();
                    var results =
                        List.of(
                            job.waitForJob(ok, String.class),
                            job.waitForJob(failed, String.class),
                            job.waitForJob(cancelled, String.class),
                            job.waitForJob(compensationFailed, String.class));
                    outcomes.addAll(results);
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(outcomes).hasSize(4);
      assertThat(outcomes.get(0)).isInstanceOf(JobResult.Succeeded.class);
      assertThat(outcomes.get(0).orElseThrow()).isEqualTo("\"ok\"");
      assertThat(outcomes.get(0).isSucceeded()).isTrue();
      assertThat(outcomes.get(1))
          .isInstanceOfSatisfying(
              JobResult.Failed.class,
              f -> assertThat(f.error()).isEqualTo("java.lang.IllegalStateException: broken"));
      assertThat(outcomes.get(2))
          .isInstanceOfSatisfying(
              JobResult.Cancelled.class, c -> assertThat(c.message()).isEqualTo("not needed"));
      assertThat(outcomes.get(3))
          .isInstanceOfSatisfying(
              JobResult.CompensationFailed.class,
              c -> assertThat(c.error()).contains("cannot undo"));
      assertThatThrownBy(outcomes.get(1)::orElseThrow)
          .isInstanceOfSatisfying(
              JobFailedException.class,
              e -> {
                assertThat(e.jobId()).isEqualTo(outcomes.get(1).jobId());
                assertThat(e.result()).isEqualTo(outcomes.get(1));
              })
          .hasMessageEndingWith("failed: java.lang.IllegalStateException: broken");
      assertThat(outcomes)
          .extracting(outcome -> outcome.orElse("\"fallback\""))
          .as("orElse: the result of a succeeded child, the fallback otherwise")
          .containsExactly("\"ok\"", "\"fallback\"", "\"fallback\"", "\"fallback\"");
      assertThat(new JobResult.TimedOut<String>(parentId).orElse("\"fallback\""))
          .isEqualTo("\"fallback\"");
    }
  }

  @Test
  void aChildThatEndedBeforeTheWaitReturnsRightAway() throws Exception {
    var runs = new AtomicInteger();
    var outcome = new AtomicReference<JobResult<String>>();
    try (Bikesched bikesched = testNode("node-child-ended-before").build()) {
      bikesched.registerJobHandler("child-ended-before-child", job -> job.complete("\"x\""));
      UUID parentId =
          bikesched
              .newJob("child-ended-before-parent")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    UUID child = job.newJob("child-ended-before-child").schedule();
                    job.step(
                        "until the child ended",
                        Void.class,
                        ignored -> {
                          awaitState(child, "SUCCEEDED");
                          return null;
                        });
                    outcome.set(job.waitForJob(child, String.class));
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(runs).as("no suspension").hasValue(1);
      assertThat(outcome.get().orElseThrow()).isEqualTo("\"x\"");
    }
  }

  @Test
  void aChildEndingWhileTheParentSuspendsMakesTheParentDue() throws Exception {
    var runs = new AtomicInteger();
    var parentSuspending = new AtomicBoolean();
    try (Bikesched bikesched = testNode("node-child-race").build()) {
      // The child ends only once the parent read its step as WAITING, so the parent always takes
      // the suspension path instead of finding the child ended right away.
      bikesched.registerJobHandler("child-race-child", job -> await().untilTrue(parentSuspending));
      UUID parentId =
          bikesched
              .newJob("child-race-parent")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    UUID child = job.newJob("child-race-child").schedule();
                    try {
                      job.waitForJob(child, String.class);
                    } catch (Error suspended) {
                      // The step was WAITING when read; the child ends before the suspension.
                      parentSuspending.set(true);
                      awaitState(child, "SUCCEEDED");
                      throw suspended;
                    }
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(runs).hasValue(2);
    }
  }

  @Test
  void aChildEndingWhileTheParentWaitsForAnotherOneCausesOneExtraReplay() throws Exception {
    var runs = new AtomicInteger();
    List<String> results = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-child-parallel").build()) {
      bikesched.registerJobHandler("child-parallel-child", job -> job.complete(job.payload()));
      UUID parentId =
          bikesched
              .newJob("child-parallel-parent")
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    UUID slow =
                        job.newJob("child-parallel-child")
                            .stepName("slow")
                            .payload("\"slow\"")
                            .schedule(Duration.ofMillis(1500));
                    UUID fast =
                        job.newJob("child-parallel-child")
                            .stepName("fast")
                            .payload("\"fast\"")
                            .schedule(Duration.ofMillis(500));
                    String a = job.waitForJob(slow, String.class).orElseThrow();
                    String b = job.waitForJob(fast, String.class).orElseThrow();
                    results.add(a + b);
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(results).containsExactly("\"slow\"\"fast\"");
      assertThat(runs).as("started, woken by the fast child, resumed by the slow one").hasValue(3);
    }
  }

  @Test
  void theOutcomeSurvivesTheDeletionOfTheChild() throws Exception {
    var outcome = new AtomicReference<JobResult<String>>();
    var child = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode("node-child-deleted").build()) {
      bikesched.registerJobHandler("child-deleted-child", job -> job.complete("\"kept\""));
      UUID parentId =
          bikesched
              .newJob("child-deleted-parent")
              .handler(
                  job -> {
                    child.set(job.newJob("child-deleted-child").schedule());
                    job.sleep("nap", Duration.ofSeconds(1));
                    outcome.set(job.waitForJob(child.get(), String.class));
                  })
              .schedule();

      await().until(() -> child.get() != null);
      awaitState(child.get(), "SUCCEEDED");
      execute("delete from bikesched_jobs where id = '" + child.get() + "'"); // job retention
      awaitState(parentId, "SUCCEEDED");
      assertThat(outcome.get()).isEqualTo(new JobResult.Succeeded<>(child.get(), "\"kept\""));
    }
  }

  // ---- Rejected uses ----

  @Test
  void waitingForAJobThatIsNotAChildIsRejected() throws Exception {
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-not-a-child").build()) {
      UUID other = bikesched.newJob("child-not-a-child-other").handler(() -> {}).schedule();
      UUID parentId =
          bikesched
              .newJob("child-not-a-child")
              .handler(
                  job -> {
                    try {
                      job.waitForJob(other, String.class);
                    } catch (IllegalArgumentException e) {
                      failure.set(e);
                    }
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(failure.get())
          .hasMessage(
              "Job "
                  + other
                  + " is not a child of job "
                  + parentId
                  + ": a job can only wait for the jobs it started with job.newJob(…)");
    }
  }

  @Test
  void occurrencesOfRecurringJobsCannotStartChildren() throws Exception {
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-recurring").build()) {
      bikesched.registerJobHandler("child-recurring-child", job -> {});
      bikesched
          .newJob("child-recurring")
          .handler(
              job -> {
                try {
                  job.newJob("child-recurring-child");
                } catch (IllegalStateException e) {
                  failure.compareAndSet(null, e);
                }
              })
          .schedule(Recurrence.every(Duration.ofHours(1)), Duration.ZERO);

      await().until(() -> failure.get() != null);
      assertThat(failure.get()).hasMessageContaining("cannot start child jobs");
      assertThat(count("type = 'child-recurring-child'")).isZero();
    }
  }

  @Test
  void startingAChildInsideAStepIsRejected() throws Exception {
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-in-step").build()) {
      bikesched.registerJobHandler("child-in-step-child", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-in-step")
              .handler(
                  job ->
                      job.step(
                          "outer",
                          Void.class,
                          ignored -> {
                            try {
                              job.newJob("child-in-step-child").schedule();
                            } catch (IllegalStateException e) {
                              failure.set(e);
                            }
                            return null;
                          }))
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(failure.get())
          .hasMessageContaining("cannot start a child job of type child-in-step-child while");
    }
  }

  @Test
  void aChildTypeNeedsARegisteredHandler() throws Exception {
    assertThat(StepJobBuilder.class.getMethods()).noneMatch(m -> m.getName().equals("handler"));
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-no-handler").build()) {
      UUID parentId =
          bikesched
              .newJob("child-no-handler")
              .handler(
                  job -> {
                    try {
                      job.newJob("child-no-handler-child").schedule();
                    } catch (IllegalArgumentException e) {
                      failure.set(e);
                    }
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(failure.get()).hasMessageContaining("child-no-handler-child");
    }
  }

  @Test
  void aStepCannotStartAChildOfAnotherTypeAfterItsChildWasDeleted() throws Exception {
    var child = new AtomicReference<UUID>();
    var failure = new AtomicReference<Exception>();
    var deleted = new AtomicBoolean();
    try (Bikesched bikesched = testNode("node-child-type").build()) {
      bikesched.registerJobHandler("child-type-a", job -> {});
      bikesched.registerJobHandler("child-type-b", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-type-parent")
              .handler(
                  job -> {
                    String type = deleted.get() ? "child-type-b" : "child-type-a";
                    try {
                      child.set(job.newJob(type).stepName("child").schedule());
                    } catch (IllegalStateException e) {
                      failure.set(e);
                      return;
                    }
                    job.waitForEvent("go", "child-type-go", "k", String.class).receive();
                  })
              .schedule();

      // the child's end completed the step, and resumed the parent if it was waiting already
      await()
          .until(
              () ->
                  countSteps(parentId, "name = 'child' and state = 'SUCCEEDED'") == 1
                      && count("id = '" + parentId + "' and state = 'WAITING'") == 1);
      execute("delete from bikesched_jobs where id = '" + child.get() + "'"); // job retention
      deleted.set(true);
      bikesched.newEvent("child-type-go", "k").payload("\"go\"").publish();
      awaitState(parentId, "SUCCEEDED");
      assertThat(failure.get())
          .hasMessageContaining("of type child-type-a, not of type child-type-b");
    }
  }

  @Test
  void startingTwoChildrenWithTheSameStepNameIsRejected() throws Exception {
    var failure = new AtomicReference<Exception>();
    try (Bikesched bikesched = testNode("node-child-duplicate").build()) {
      bikesched.registerJobHandler("child-duplicate-child", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-duplicate")
              .handler(
                  job -> {
                    job.newJob("child-duplicate-child").schedule();
                    try {
                      job.newJob("child-duplicate-child").schedule();
                    } catch (IllegalStateException e) {
                      failure.set(e);
                    }
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(failure.get())
          .hasMessageContaining("with step name 'start child-duplicate-child'");
      assertThat(count("parent_job_id = '" + parentId + "'")).isEqualTo(1);
    }
  }

  @Test
  void aChildCannotBeRetried() throws Exception {
    var child = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode("node-child-retry").build()) {
      bikesched.registerJobHandler(
          "child-retry-child",
          job -> {
            throw new IllegalStateException("broken");
          });
      UUID parentId =
          bikesched
              .newJob("child-retry-parent")
              .handler(
                  job -> {
                    child.set(job.newJob("child-retry-child").maxAttempts(1).schedule());
                    job.waitForJob(child.get(), String.class);
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThatThrownBy(() -> bikesched.retry(child.get()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is a child of job " + parentId);
      assertThat(count("id = '" + child.get() + "' and state = 'FAILED'")).isEqualTo(1);
    }
  }

  // ---- Cancellation, compensation, deletion ----

  @Test
  void cancellingTheParentLeavesTheChildAlone() throws Exception {
    var child = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode("node-child-cancel-parent").build()) {
      bikesched.registerJobHandler(
          "child-cancel-parent-child",
          job -> job.waitForEvent("go", "child-cancel-parent-go", "k", String.class).receive());
      UUID parentId =
          bikesched
              .newJob("child-cancel-parent")
              .handler(
                  job -> {
                    child.set(job.newJob("child-cancel-parent-child").schedule());
                    job.waitForJob(child.get(), String.class);
                  })
              .schedule();
      awaitState(parentId, "WAITING");
      awaitState(child.get(), "WAITING");

      assertThat(bikesched.cancel(parentId, "stop")).isTrue();
      awaitState(parentId, "CANCELLED");
      assertThat(countSteps(parentId, "kind = 'CHILD_JOB' and state = 'CANCELLED'")).isEqualTo(1);
      assertThat(count("id = '" + child.get() + "' and state = 'WAITING'")).isEqualTo(1);

      bikesched.newEvent("child-cancel-parent-go", "k").payload("\"go\"").publish();
      awaitState(child.get(), "SUCCEEDED");
      assertThat(countSteps(parentId, "kind = 'CHILD_JOB' and state = 'CANCELLED'"))
          .as("the child's end leaves the cancelled step as it is")
          .isEqualTo(1);
    }
  }

  @Test
  void aCompensationReceivesTheIdOfTheRunningChild() throws Exception {
    var child = new AtomicReference<UUID>();
    List<UUID> compensated = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = testNode("node-child-compensate").build()) {
      bikesched.registerJobHandler(
          "child-compensate-child",
          job -> job.waitForEvent("go", "child-compensate-go", "k", String.class).receive());
      UUID parentId =
          bikesched
              .newJob("child-compensate")
              .handler(
                  job -> {
                    child.set(
                        job.newJob("child-compensate-child")
                            .compensateWith(
                                (ignored, childId) -> {
                                  compensated.add(childId);
                                  bikesched.cancel(childId, "parent cancelled");
                                })
                            .schedule());
                    job.waitForJob(child.get(), String.class);
                  })
              .schedule();
      awaitState(parentId, "WAITING");

      bikesched.cancel(parentId, "stop");
      awaitState(parentId, "CANCELLED");
      awaitState(child.get(), "CANCELLED");
      assertThat(compensated).containsExactly(child.get());
      assertThat(countSteps(parentId, "kind = 'CHILD_JOB' and state = 'COMPENSATED'")).isEqualTo(1);
      assertThat(count("id = '" + child.get() + "' and last_error = 'parent cancelled'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aRetriedParentStartsANewChildForACompensatedStep() throws Exception {
    var runs = new AtomicInteger();
    var childIds = new CopyOnWriteArrayList<UUID>();
    try (Bikesched bikesched = testNode("node-child-retry-parent").build()) {
      bikesched.registerJobHandler("child-retry-parent-child", job -> {});
      UUID parentId =
          bikesched
              .newJob("child-retry-parent")
              .maxAttempts(1)
              .handler(
                  job -> {
                    UUID child =
                        job.newJob("child-retry-parent-child")
                            .compensateWith((ignored, ignored2) -> {})
                            .schedule();
                    childIds.addIfAbsent(child);
                    if (!job.isCompensating() && runs.incrementAndGet() == 1) {
                      throw new IllegalStateException("fail permanently");
                    }
                  })
              .schedule();
      awaitState(parentId, "FAILED");
      assertThat(countSteps(parentId, "kind = 'CHILD_JOB' and state = 'COMPENSATED'")).isEqualTo(1);

      assertThat(bikesched.retry(parentId)).isTrue();
      awaitState(parentId, "SUCCEEDED");
      assertThat(childIds).hasSize(2);
      assertThat(count("parent_job_id = '" + parentId + "'")).isEqualTo(2);
      assertThat(
              countSteps(
                  parentId,
                  "kind = 'CHILD_JOB' and attempts = 2 and child_job_id = '"
                      + childIds.get(1)
                      + "'"))
          .isEqualTo(1);
    }
  }

  @Test
  void aChildOutlivesItsDeletedParent() throws Exception {
    var child = new AtomicReference<UUID>();
    try (Bikesched bikesched = testNode("node-child-orphan").build()) {
      bikesched.registerJobHandler(
          "child-orphan-child",
          job -> job.waitForEvent("go", "child-orphan-go", "k", String.class).receive());
      UUID parentId =
          bikesched
              .newJob("child-orphan-parent")
              .handler(job -> child.set(job.newJob("child-orphan-child").schedule()))
              .schedule();
      awaitState(parentId, "SUCCEEDED"); // fire-and-forget
      awaitState(child.get(), "WAITING");

      execute("delete from bikesched_jobs where id = '" + parentId + "'"); // job retention
      bikesched.newEvent("child-orphan-go", "k").payload("\"go\"").publish();
      awaitState(child.get(), "SUCCEEDED");
      assertThat(bikesched.findJob(child.get()).orElseThrow().parentJobId()).contains(parentId);
    }
  }

  // ---- Wait timeouts ----

  @Test
  void aWaitTimesOutAndStaysTimedOut() throws Exception {
    var child = new AtomicReference<UUID>();
    List<JobResult<String>> outcomes = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        testNode("node-child-timeout").jobHistory(Duration.ofHours(1)).build()) {
      bikesched.registerJobHandler(
          "child-timeout-child",
          job -> job.waitForEvent("go", "child-timeout-go", "k", String.class).receive());
      var timeout = new AtomicReference<>(Duration.ofMillis(300));
      UUID parentId =
          bikesched
              .newJob("child-timeout-parent")
              .handler(
                  job -> {
                    child.set(job.newJob("child-timeout-child").schedule());
                    outcomes.add(job.waitForJob(child.get(), String.class, timeout.get()));
                    timeout.set(Duration.ofHours(1)); // ignored: the stored deadline wins
                    job.sleep("nap", Duration.ofMillis(200));
                    outcomes.add(job.waitForJob(child.get(), String.class, timeout.get()));
                  })
              .schedule();

      awaitState(parentId, "SUCCEEDED");
      assertThat(outcomes)
          .hasSizeGreaterThanOrEqualTo(3)
          .containsOnly(new JobResult.TimedOut<>(child.get()));
      assertThat(
              countSteps(
                  parentId,
                  "kind = 'CHILD_JOB' and state = 'TIMED_OUT'"
                      + " and wait_until < started_at + interval '1 minute'"))
          .isEqualTo(1);
      assertThat(count("id = '" + child.get() + "' and state = 'WAITING'")).isEqualTo(1);
      assertThatThrownBy(outcomes.getFirst()::orElseThrow)
          .isInstanceOf(JobFailedException.class)
          .hasMessage("Waiting for job " + child.get() + " timed out");

      bikesched.newEvent("child-timeout-go", "k").payload("\"go\"").publish();
      awaitState(child.get(), "SUCCEEDED");
      assertThat(countSteps(parentId, "kind = 'CHILD_JOB' and state = 'TIMED_OUT'"))
          .as("the child's end leaves the timed-out step as it is")
          .isEqualTo(1);
      assertThat(bikesched.findJobHistory(parentId))
          .extracting(ChildJobTest::describe)
          .anyMatch(
              entry ->
                  entry.startsWith(
                      "start child-timeout-child WAITING waiting for job "
                          + child.get()
                          + " until"))
          .contains("start child-timeout-child TIMED_OUT waiting for job " + child.get());
    }
  }

  private static String describe(JobHistoryEntry entry) {
    return (entry.stepName() == null ? "job" : entry.stepName())
        + " "
        + entry.state()
        + (entry.detail() == null ? "" : " " + entry.detail());
  }

  private static long countSteps(UUID jobId, String where) throws SQLException {
    return countRows("bikesched_steps", "job_id = '" + jobId + "' and " + where);
  }
}
