package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.DefaultBikesched;
import org.bikesched.internal.job.HistoryCleanUp;
import org.junit.jupiter.api.Test;

/**
 * The job history: {@code jobHistory(retention)} records every change of a job and its steps, read
 * with {@link Bikesched#findJobHistory(UUID)}; without it nothing is recorded.
 */
class JobHistoryTest extends AbstractBikeschedTest {

  @Test
  void succeededJobRecordsItsStepsInOrder() throws Exception {
    try (Bikesched bikesched = node("node-history-steps")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-steps"))
              .handler(
                  job -> {
                    job.step("reserve", String.class, _ -> "\"r-1\"");
                    job.step("charge", String.class, _ -> "\"p-1\"");
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly(
              "SCHEDULED",
              "RUNNING",
              "reserve:RUNNING",
              "reserve:SUCCEEDED",
              "charge:RUNNING",
              "charge:SUCCEEDED",
              "SUCCEEDED");
      assertThat(history).extracting(JobHistoryEntry::attempt).containsExactly(0, 1, 1, 1, 1, 1, 1);
      UUID nodeId = bikesched.node().id().orElseThrow();
      assertThat(history.getFirst().nodeId()).isNull(); // scheduled by the application
      assertThat(history.getFirst().detail()).startsWith("due at ");
      assertThat(history.subList(1, history.size()))
          .extracting(JobHistoryEntry::nodeId)
          .containsOnly(nodeId);
      assertThat(history.get(2).isStepEntry()).isTrue();
      assertThat(history.get(1).isStepEntry()).isFalse();
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
                  _ -> {
                    if (calls.incrementAndGet() == 1) {
                      throw new IllegalStateException("broker unavailable");
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly("SCHEDULED", "RUNNING", "SCHEDULED", "RUNNING", "SUCCEEDED");
      assertThat(history).extracting(JobHistoryEntry::attempt).containsExactly(0, 1, 1, 2, 2);
      assertThat(history.get(2).detail())
          .startsWith("java.lang.IllegalStateException: broker unavailable (retry at ");
    }
  }

  @Test
  void eventWaitRecordsSuspendingAndResuming() throws Exception {
    String event = unique("history-event");
    try (Bikesched bikesched = node("node-history-event")) {
      UUID jobId =
          bikesched
              .newJob(unique("history-wait"))
              .handler(job -> job.waitForEvent("await", event, "order-1").receive(String.class))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(event, "order-1").payload("\"paid\"").publish();
      awaitState(jobId, "SUCCEEDED");

      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly(
              "SCHEDULED",
              "RUNNING",
              "await:WAITING",
              "WAITING",
              "await:SUCCEEDED",
              "SCHEDULED",
              "RUNNING",
              "SUCCEEDED");
      // Suspending gives back the attempt; resuming counts a new one.
      assertThat(history)
          .extracting(JobHistoryEntry::attempt)
          .containsExactly(0, 1, 1, 0, 0, 0, 1, 1);
      assertThat(history.get(2).detail()).isEqualTo("waiting for event " + event + "/order-1");
      assertThat(history.get(3).detail()).isEqualTo("suspended at step 'await'");
      // The step stores the event it received; publishing is no node's change.
      String eventId = eventId(jobId, "await");
      assertThat(history.get(4).detail()).isEqualTo("received event " + eventId);
      assertThat(history.get(5).detail()).isEqualTo("resumed by event " + eventId);
      assertThat(history.get(4).nodeId()).isNull();
      assertThat(history.get(5).nodeId()).isNull();
      assertThat(countRows("bikesched_events", "id = '" + eventId + "'")).isEqualTo(1);
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

      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly("SCHEDULED", "SCHEDULED", "RUNNING", "CANCELLED");
      assertThat(history.get(1).detail()).isEqualTo("cancellation requested: no longer needed");
      assertThat(history.get(1).nodeId()).isNull();
      assertThat(history.get(2).detail()).isEqualTo("cancellation requested");
      assertThat(history.get(3).detail()).isEqualTo("no longer needed");
      // Cancelled when claimed, without running the handler: the attempt is given back.
      assertThat(history).extracting(JobHistoryEntry::attempt).containsExactly(0, 0, 1, 0);
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
                        .compensateWith((_, _) -> {})
                        .run(_ -> "\"r-1\"");
                    throw new IllegalStateException("payment declined");
                  })
              .schedule();
      awaitState(jobId, "FAILED");

      List<JobHistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(changes(history))
          .containsExactly(
              "SCHEDULED",
              "RUNNING",
              "reserve:RUNNING",
              "reserve:SUCCEEDED",
              "COMPENSATING",
              "RUNNING",
              "reserve:COMPENSATED",
              "FAILED");
      assertThat(history.get(4).detail())
          .isEqualTo("java.lang.IllegalStateException: payment declined");
      assertThat(history.get(5).detail()).isEqualTo("compensation attempt 1");
      assertThat(history.get(7).detail()).isEqualTo("compensated");
    }
  }

  @Test
  void nothingIsRecordedWithoutTheHistory() throws Exception {
    try (Bikesched bikesched = testNode("node-history-off").build()) {
      UUID jobId =
          bikesched
              .newJob(unique("history-off"))
              .handler(job -> job.step("step", String.class, _ -> "\"x\""))
              .schedule();
      awaitState(jobId, "SUCCEEDED");

      assertThat(bikesched.findJobHistory(jobId)).isEmpty();
      assertThat(countRows("bikesched_job_history", "job_id = '" + jobId + "'")).isZero();
    }
  }

  @Test
  void entriesOlderThanTheRetentionAreDeleted() throws Exception {
    UUID oldJob = UUID.randomUUID();
    UUID recentJob = UUID.randomUUID();
    execute(
        "insert into bikesched_job_history (job_id, created_at, state, attempt) values ('"
            + oldJob
            + "', now() - interval '2 hours', 'SCHEDULED', 0), ('"
            + recentJob
            + "', now(), 'SCHEDULED', 0)");
    var logs = LogCapture.of(DefaultBikesched.class, HistoryCleanUp.class);
    try (logs;
        var _ =
            testNode("node-history-cleanup")
                .jobHistory(Duration.ofHours(1))
                .jobHistoryCleanUpInterval(Duration.ofMillis(100))
                .build()) {
      await()
          .untilAsserted(
              () -> assertThat(countRows("bikesched_job_history", jobIn(oldJob))).isZero());
      assertThat(countRows("bikesched_job_history", jobIn(recentJob))).isEqualTo(1);
      assertThat(logs.messages())
          .contains(
              "bikesched node node-history-cleanup records the job history, keeping entries for PT1H")
          .anyMatch(m -> m.matches("Deleted \\d+ job history entries older than PT1H"));
    } finally {
      execute("delete from bikesched_job_history where " + jobIn(recentJob));
    }
  }

  /** A node recording the job history. */
  private static Bikesched node(String name) {
    return testNode(name).jobHistory(Duration.ofDays(1)).build();
  }

  /** The changes as {@code STATE} (job) or {@code step:STATE} (step). */
  private static List<String> changes(List<JobHistoryEntry> history) {
    return history.stream()
        .map(e -> e.isStepEntry() ? e.stepName() + ":" + e.state() : e.state())
        .toList();
  }

  private static String eventId(UUID jobId, String stepName) throws Exception {
    try (var connection = dataSource.getConnection();
        var query =
            connection.prepareStatement(
                "select event_id from bikesched_steps where job_id = ? and name = ?")) {
      query.setObject(1, jobId);
      query.setString(2, stepName);
      try (var rs = query.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private static String jobIn(UUID jobId) {
    return "job_id = '" + jobId + "'";
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
