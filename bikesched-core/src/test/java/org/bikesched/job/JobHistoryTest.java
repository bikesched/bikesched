// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.bikesched.HistoryTesting.actions;
import static org.bikesched.HistoryTesting.actionsAndStates;
import static org.bikesched.HistoryTesting.data;
import static org.bikesched.HistoryTesting.entry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.history.ActorType;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobEntry;
import org.bikesched.history.StepEntry;
import org.bikesched.internal.DefaultBikesched;
import org.bikesched.internal.job.HistoryCleanUp;
import org.junit.jupiter.api.Test;

/**
 * The history of jobs: {@code history(retention)} records every change of a job and its steps with
 * its action and actor, read with {@link Bikesched#findJobHistory(UUID)}; without it nothing is
 * recorded.
 */
class JobHistoryTest extends AbstractBikeschedTest {

  @Test
  void succeededJobRecordsItsStepsInOrder() throws Exception {
    String name = unique("history-steps");
    try (Bikesched bikesched = node("node-history-steps")) {
      UUID jobId =
          bikesched
              .newJob(name)
              .handler(
                  job -> {
                    job.step("reserve", String.class, ignored -> "\"r-1\"");
                    job.step("charge", String.class, ignored -> "\"p-1\"");
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actionsAndStates(history))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CLAIMED:RUNNING",
              "reserve STARTED:RUNNING",
              "reserve SUCCEEDED:SUCCEEDED",
              "charge STARTED:RUNNING",
              "charge SUCCEEDED:SUCCEEDED",
              "SUCCEEDED:SUCCEEDED");
      assertThat(history)
          .extracting(entry -> entry instanceof JobEntry job ? job.attempt() : -1)
          .containsExactly(0, 1, -1, -1, -1, -1, 1);
      assertThat(history)
          .filteredOn(StepEntry.class::isInstance)
          .extracting(entry -> ((StepEntry) entry).attempt())
          .containsOnly(1);
      assertThat(history)
          .extracting(HistoryEntry::actorType)
          .containsExactly(
              ActorType.APPLICATION,
              ActorType.NODE,
              ActorType.JOB,
              ActorType.JOB,
              ActorType.JOB,
              ActorType.JOB,
              ActorType.NODE);
      // The polling node wrote every entry, also the one of scheduling through its API.
      assertThat(history).extracting(HistoryEntry::nodeId).containsOnly(bikesched.node().id());
      assertThat(history).extracting(entry -> data(entry, "subjectName")).containsOnly(name);
      assertThat(data(history.getFirst(), "dueAt")).isNotNull();
      assertThat(history.get(1).data()).isEqualTo("{\"subjectName\": \"" + name + "\"}");
      assertThat(history).extracting(HistoryEntry::causedById).containsOnlyNulls();
      assertThat(history.get(2)).isInstanceOf(StepEntry.class);
      assertThat(((StepEntry) history.get(2)).stepId())
          .isEqualTo(((StepEntry) history.get(3)).stepId());
    }
  }

  @Test
  void failedAttemptIsRecordedWithItsErrorAndRetry() throws Exception {
    var calls = new AtomicInteger();
    try (Bikesched bikesched = node("node-history-retry")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-retry"))
              .maxAttempts(2)
              .handler(
                  ignored -> {
                    if (calls.incrementAndGet() == 1) {
                      throw new IllegalStateException("broker unavailable");
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actionsAndStates(history))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CLAIMED:RUNNING",
              "ATTEMPT_FAILED:SCHEDULED",
              "CLAIMED:RUNNING",
              "SUCCEEDED:SUCCEEDED");
      assertThat(history)
          .extracting(entry -> ((JobEntry) entry).attempt())
          .containsExactly(0, 1, 1, 2, 2);
      assertThat(data(history.get(2), "error"))
          .isEqualTo("java.lang.IllegalStateException: broker unavailable");
      assertThat(data(history.get(2), "dueAt")).isNotNull();
    }
  }

  @Test
  void eventWaitRecordsSuspendingAndResuming() throws Exception {
    String event = unique("history-event");
    try (Bikesched bikesched = node("node-history-event")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-wait"))
              .handler(job -> job.waitForEvent("await", event, "order-1", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(event, "order-1").payload("\"paid\"").publish();
      awaitState(jobId, "SUCCEEDED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actionsAndStates(history))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CLAIMED:RUNNING",
              "await WAITING:WAITING",
              "SUSPENDED:WAITING",
              "await RECEIVED:SUCCEEDED",
              "RESUMED:SCHEDULED",
              "CLAIMED:RUNNING",
              "SUCCEEDED:SUCCEEDED");
      // Suspending gives back the attempt; resuming counts a new one.
      assertThat(history)
          .filteredOn(JobEntry.class::isInstance)
          .extracting(entry -> ((JobEntry) entry).attempt())
          .containsExactly(0, 1, 0, 0, 1, 1);
      assertThat(data(history.get(2), "eventName")).isEqualTo(event);
      assertThat(data(history.get(2), "eventKey")).isEqualTo("order-1");
      assertThat(data(history.get(3), "stepName")).isEqualTo("await");
      // Publishing completes the step and resumes the job: the publisher's changes.
      assertThat(history.get(4).actorType()).isEqualTo(ActorType.APPLICATION);
      assertThat(history.get(5).actorType()).isEqualTo(ActorType.APPLICATION);
    }
  }

  @Test
  void cancelRequestAndCancellationAreRecorded() throws Exception {
    try (Bikesched bikesched = node("node-history-cancel")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-cancel"))
              .handler(() -> {})
              .schedule(Duration.ofHours(1));

      assertThat(bikesched.cancel(jobId, "no longer needed")).isTrue();
      awaitState(jobId, "CANCELLED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actionsAndStates(history))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CANCEL_REQUESTED:SCHEDULED",
              "CLAIMED:RUNNING",
              "CANCELLED:CANCELLED");
      assertThat(history.get(1).actorType()).isEqualTo(ActorType.APPLICATION);
      assertThat(data(history.get(1), "message")).isEqualTo("no longer needed");
      assertThat(data(history.get(2), "reason")).isEqualTo("CANCELLATION");
      assertThat(data(history.get(3), "message")).isEqualTo("no longer needed");
      // Cancelled when claimed, without running the handler: the attempt is given back.
      assertThat(history)
          .extracting(entry -> ((JobEntry) entry).attempt())
          .containsExactly(0, 0, 1, 0);
    }
  }

  @Test
  void cancellingAWaitingJobCancelsItsStep() throws Exception {
    try (Bikesched bikesched = node("node-history-cancel-wait")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-cancel-wait"))
              .handler(
                  job -> job.waitForEvent("await", unique("never"), "k", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.cancel(jobId);
      awaitState(jobId, "CANCELLED");

      HistoryEntry cancelled = entry(bikesched.findJobHistory(jobId), "await CANCELLED");
      assertThat(cancelled.actorType()).isEqualTo(ActorType.NODE);
      assertThat(((StepEntry) cancelled).state()).isEqualTo(StepState.CANCELLED);
    }
  }

  @Test
  void compensationIsRecorded() throws Exception {
    try (Bikesched bikesched = node("node-history-compensation")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-compensation"))
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith((ignored, ignored2) -> {})
                        .run(ignored -> "\"r-1\"");
                    throw new IllegalStateException("payment declined");
                  })
              .schedule();
      awaitState(jobId, "FAILED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actionsAndStates(history))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CLAIMED:RUNNING",
              "reserve STARTED:RUNNING",
              "reserve SUCCEEDED:SUCCEEDED",
              "COMPENSATING:COMPENSATING",
              "CLAIMED:RUNNING",
              "reserve COMPENSATED:COMPENSATED",
              "COMPENSATED:FAILED");
      assertThat(data(history.get(4), "error"))
          .isEqualTo("java.lang.IllegalStateException: payment declined");
      assertThat(data(history.get(5), "reason")).isEqualTo("COMPENSATION");
      assertThat(data(history.get(5), "run")).isEqualTo("1");
    }
  }

  @Test
  void failedStepAndFailedCompensationAreRecorded() throws Exception {
    var compensations = new AtomicInteger();
    try (Bikesched bikesched = node("node-history-compensation-failed")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-compensation-failed"))
              .maxAttempts(2)
              .handler(
                  job -> {
                    job.newStep("reserve", String.class)
                        .compensateWith(
                            (ignored, ignored2) -> {
                              if (compensations.incrementAndGet() == 1) {
                                throw new IllegalStateException("release failed");
                              }
                            })
                        .run(ignored -> "\"r-1\"");
                    job.step(
                        "charge",
                        String.class,
                        ignored -> {
                          throw new IllegalStateException("payment declined");
                        });
                  })
              .schedule();
      awaitState(jobId, "FAILED");

      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(actions(history))
          .contains(
              "charge FAILED",
              "reserve COMPENSATION_FAILED",
              "COMPENSATION_FAILED",
              "reserve COMPENSATED",
              "COMPENSATED");
      assertThat(data(entry(history, "charge FAILED"), "error"))
          .isEqualTo("java.lang.IllegalStateException: payment declined");
      HistoryEntry stepFailed = entry(history, "reserve COMPENSATION_FAILED");
      assertThat(((StepEntry) stepFailed).state()).isEqualTo(StepState.SUCCEEDED);
      assertThat(data(stepFailed, "error")).contains("release failed");
      HistoryEntry jobFailed = entry(history, "COMPENSATION_FAILED");
      assertThat(((JobEntry) jobFailed).state()).isEqualTo(JobState.COMPENSATING);
      assertThat(data(jobFailed, "dueAt")).isNotNull();
    }
  }

  @Test
  void publishingStepIsRecorded() throws Exception {
    String event = unique("history-published");
    try (Bikesched bikesched = node("node-history-publish")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-publisher"))
              .handler(job -> job.newEvent(event, "k").stepName("publish").publish())
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      HistoryEntry published = entry(bikesched.findJobHistory(jobId), "publish PUBLISHED");
      assertThat(published.actorType()).isEqualTo(ActorType.JOB);
      assertThat(data(published, "eventId")).isNotNull();
    }
  }

  @Test
  void movedAndDeletedOccurrencesAreRecorded() throws Exception {
    String name = unique("history-recurring");
    UUID occurrence;
    try (Bikesched first = notPolling("node-history-recurring-1")) {
      occurrence =
          first
              .newJob(name)
              .handler(ignored -> {})
              .schedule(Recurrence.every(Duration.ofHours(1)), Duration.ofHours(1));
    }
    try (Bikesched second = notPolling("node-history-recurring-2")) {
      second
          .newJob(name)
          .handler(ignored -> {})
          .schedule(Recurrence.every(Duration.ofHours(2)), Duration.ofHours(1));
      second.removeRecurringJob(name);

      List<HistoryEntry> history = second.findJobHistory(occurrence);
      assertThat(actionsAndStates(history))
          .containsExactly("SCHEDULED:SCHEDULED", "MOVED:SCHEDULED", "DELETED:SCHEDULED");
      assertThat(data(history.getFirst(), "recurrence")).isEqualTo("every PT1H");
      assertThat(data(history.get(1), "recurrence")).isEqualTo("every PT2H");
      assertThat(data(history.get(1), "previousRecurrence")).isEqualTo("every PT1H");
      // The job is gone: its name was stored with the entry.
      assertThat(second.findJob(occurrence)).isEmpty();
      assertThat(data(history.get(2), "subjectName")).isEqualTo(name);
      assertThat(history).extracting(HistoryEntry::actorType).containsOnly(ActorType.APPLICATION);
      // Not polling: not registered as a node.
      assertThat(history).extracting(HistoryEntry::nodeId).containsOnlyNulls();
    }
  }

  @Test
  void nothingIsRecordedWithoutTheHistory() throws Exception {
    try (Bikesched bikesched = testNode("node-history-off").build()) {
      UUID jobId =
          bikesched
              .newJob(unique("history-off"))
              .handler(job -> job.step("step", String.class, ignored -> "\"x\""))
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.findJobHistory(jobId)).isEmpty();
      assertThat(countRows("bikesched_history", subject(jobId))).isZero();
    }
  }

  @Test
  void entriesOlderThanTheRetentionAreDeleted() throws Exception {
    UUID oldJob = UUID.randomUUID();
    UUID recentJob = UUID.randomUUID();
    execute(
        "insert into bikesched_history"
            + " (subject_id, id, subject_type, action, state, attempt, actor_type, created_at)"
            + " values ('"
            + oldJob
            + "', gen_random_uuid(), 'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'APPLICATION',"
            + " now() - interval '2 hours'), ('"
            + recentJob
            + "', gen_random_uuid(), 'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'APPLICATION', now())");
    var logs = LogCapture.of(DefaultBikesched.class, HistoryCleanUp.class);
    try (logs;
        var ignored =
            testNode("node-history-cleanup")
                .history(Duration.ofHours(1))
                .historyCleanUpInterval(Duration.ofMillis(100))
                .build()) {
      await()
          .untilAsserted(
              () -> assertThat(countRows("bikesched_history", subject(oldJob))).isZero());
      assertThat(countRows("bikesched_history", subject(recentJob))).isEqualTo(1);
      assertThat(logs.messages())
          .contains(
              "bikesched node node-history-cleanup records the history, keeping entries for PT1H")
          .anyMatch(m -> m.matches("Deleted \\d+ history entries older than PT1H"));
    } finally {
      execute("delete from bikesched_history where " + subject(recentJob));
    }
  }

  /** A polling node recording the history. */
  private static Bikesched node(String name) {
    return testNode(name).history(Duration.ofDays(1)).build();
  }

  /** A node recording the history that does not poll. */
  private static Bikesched notPolling(String name) {
    return testNode(name).autoStartPolling(false).history(Duration.ofDays(1)).build();
  }

  private static String subject(UUID subjectId) {
    return "subject_id = '" + subjectId + "'";
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
