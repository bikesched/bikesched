// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.HistoryTesting;
import org.bikesched.history.ActorType;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobAction;
import org.bikesched.history.JobEntry;
import org.bikesched.history.StepEntry;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/**
 * The {@link HistoryStore} and the changes the other stores record in it, for the paths the
 * lifecycle tests in {@code JobHistoryTest} do not reach. The store tests run with the history
 * enabled (see {@link AbstractStoreTest}).
 */
class HistoryStoreTest extends AbstractStoreTest {

  @Test
  void jobsReleasedFromADeadNodeAreRecordedByTheReleasingNode() throws SQLException {
    UUID dead = registerNode("dead");
    UUID caller = registerNode("caller");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute' where id = '"
            + dead
            + "'");
    UUID jobId = scheduleAndClaim(dead);

    writtenBy(caller).jobs().releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30));

    JobEntry released = (JobEntry) database.history().findJobHistory(jobId).getLast();
    assertThat(released.action()).isEqualTo(JobAction.RELEASED);
    assertThat(released.state()).isEqualTo(JobState.SCHEDULED);
    assertThat(released.attempt()).isEqualTo(1);
    assertThat(released.actorType()).isEqualTo(ActorType.NODE);
    assertThat(released.nodeId()).isEqualTo(caller);
    assertThat(HistoryTesting.data(released, "reason")).isEqualTo("NODE_DEAD");
  }

  @Test
  void failedStepAndTimedOutWaitAreRecordedForTheJobsAttempt() throws Exception {
    UUID node = registerNode("node-steps");
    UUID jobId = scheduleAndClaim(node);
    Database steps = writtenBy(node);

    steps.steps().startStep(jobId, lock(jobId), "charge");
    steps.steps().failStep(jobId, lock(jobId), step(jobId, "charge"), "card declined");
    steps.steps().startStep(jobId, lock(jobId), "charge");
    steps.events().awaitEvent(jobId, lock(jobId), "wait", "paid", "order-1", Duration.ofMillis(1));
    Thread.sleep(20);
    steps.events().awaitEvent(jobId, lock(jobId), "wait", "paid", "order-1", Duration.ofMillis(1));

    List<HistoryEntry> history = database.history().findJobHistory(jobId);
    List<HistoryEntry> stepEntries = history.subList(2, history.size());
    assertThat(HistoryTesting.actionsAndStates(stepEntries))
        .containsExactly(
            "charge STARTED:RUNNING",
            "charge FAILED:FAILED",
            "charge STARTED:RUNNING",
            "wait WAITING:WAITING",
            "wait TIMED_OUT:TIMED_OUT");
    assertThat(HistoryTesting.data(stepEntries.get(1), "error")).isEqualTo("card declined");
    assertThat(HistoryTesting.data(stepEntries.get(2), "run")).isEqualTo("2");
    HistoryEntry waiting = stepEntries.get(3);
    assertThat(HistoryTesting.data(waiting, "event.name")).isEqualTo("paid");
    assertThat(HistoryTesting.data(waiting, "event.key")).isEqualTo("order-1");
    assertThat(HistoryTesting.data(waiting, "waitUntil")).isNotNull();
    // Step changes take the job's attempt; the node is the one writing them.
    assertThat(stepEntries)
        .allSatisfy(
            entry -> {
              assertThat(entry.actorType()).isIn(ActorType.JOB, ActorType.NODE);
              assertThat(entry.nodeId()).isEqualTo(node);
            })
        .map(entry -> ((StepEntry) entry).attempt())
        .containsOnly(1);
  }

  @Test
  void deletedOccurrenceIsRecordedWithItsName() {
    Instant at = Instant.now().plusSeconds(3600);
    var registration =
        database
            .jobs()
            .registerRecurringJob(new NewJob("report", null, at, 0, 3, null, "every PT1H", at));

    assertThat(database.jobs().deletePendingOccurrences("report", null)).isEqualTo(1);

    List<HistoryEntry> history = database.history().findJobHistory(registration.jobId());
    assertThat(HistoryTesting.actions(history)).containsExactly("SCHEDULED", "DELETED");
    assertThat(history.getLast().actorType()).isEqualTo(ActorType.APPLICATION);
    assertThat(HistoryTesting.data(history.getLast(), "job.name")).isEqualTo("report");
  }

  @Test
  void longTextsAreTruncatedAndEntriesWithoutSpecificsHaveNoData() {
    UUID jobId = scheduleAndClaim(registerNode("node-data"));
    UUID missing = UUID.randomUUID();

    record(
        Change.job(jobId, JobAction.ATTEMPT_FAILED, JobState.SCHEDULED, 1, ActorType.NODE)
            .error("x".repeat(5000)),
        Change.job(missing, JobAction.RETRIED, JobState.SCHEDULED, null, ActorType.APPLICATION));

    HistoryEntry failed = database.history().findJobHistory(jobId).getLast();
    assertThat(HistoryTesting.data(failed, "error")).isEqualTo("x".repeat(4000));
    assertThat(HistoryTesting.data(failed, "job.name")).isEqualTo("history");
    JobEntry retried = (JobEntry) database.history().findJobHistory(missing).getFirst();
    assertThat(retried.data()).as("no job row to name it").isNull();
    assertThat(retried.attempt()).isZero();
  }

  @Test
  void theSchemaChecksEntries() {
    UUID jobId = UUID.randomUUID();
    assertThatThrownBy(() -> insert(jobId, "'JOB', 'PUBLISHED', 'SCHEDULED', 0, 'NODE', null"))
        .hasMessageContaining("bikesched_history_action_check");
    assertThatThrownBy(() -> insert(jobId, "'EVENT', 'PUBLISHED', 'SCHEDULED', 0, 'NODE', null"))
        .hasMessageContaining("bikesched_history_job_check");
    assertThatThrownBy(() -> insert(jobId, "'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'USER', null"))
        .hasMessageContaining("bikesched_history_actor_type_check");
    assertThatThrownBy(() -> insert(jobId, "'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'NODE', 'simon'"))
        .hasMessageContaining("bikesched_history_actor_type_check");
    assertThatThrownBy(
            () ->
                insert(
                    jobId, "'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'USER', '" + "u".repeat(201) + "'"))
        .hasMessageContaining("bikesched_history_actor_name_check");
    assertThatThrownBy(
            () ->
                execute(
                    "insert into bikesched_history (subject_id, id, subject_type, action, state,"
                        + " attempt, actor_type, data) values ('"
                        + jobId
                        + "', gen_random_uuid(), 'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'NODE',"
                        + " '[]')"))
        .hasMessageContaining("bikesched_history_data_check");
  }

  @Test
  void entriesOlderThanTheRetentionAreDeleted() throws SQLException {
    UUID jobId = UUID.randomUUID();
    execute(
        "insert into bikesched_history"
            + " (subject_id, id, subject_type, action, state, attempt, actor_type, created_at)"
            + " values ('"
            + jobId
            + "', gen_random_uuid(), 'JOB', 'SCHEDULED', 'SCHEDULED', 0, 'APPLICATION',"
            + " now() - interval '2 days'), ('"
            + jobId
            + "', gen_random_uuid(), 'JOB', 'CLAIMED', 'RUNNING', 1, 'NODE', now())");

    assertThat(database.history().deleteHistory(null, null, Duration.ofDays(1), 1000).count())
        .isEqualTo(1);

    assertThat(HistoryTesting.actions(database.history().findJobHistory(jobId)))
        .containsExactly("CLAIMED");
    assertThat(database.history().findJobHistory(UUID.randomUUID())).isEmpty();
  }

  /** Stores recording their entries as written by the node. */
  private static Database writtenBy(UUID node) {
    Database writing = new Database(dataSource, JSON_TEXT, true);
    writing.history().writtenBy(node);
    return writing;
  }

  private static void record(Change... changes) {
    database
        .history()
        .change(
            db -> {
              database.history().record(db, List.of(changes));
              return null;
            },
            e -> new BikeschedException("Recording failed", e));
  }

  /** Inserts a job entry with the given subject type, action, state, attempt and actor. */
  private static void insert(UUID jobId, String values) throws SQLException {
    execute(
        "insert into bikesched_history (subject_id, id, subject_type, action, state, attempt,"
            + " actor_type, actor_name) values ('"
            + jobId
            + "', gen_random_uuid(), "
            + values
            + ")");
  }

  /** Schedules a job and claims it for the node; returns its id. */
  private static UUID scheduleAndClaim(UUID node) {
    UUID jobId = UUID.randomUUID();
    database
        .jobs()
        .insertJob(jobId, new NewJob("history", null, Instant.now(), 0, 3, null, null, null));
    assertThat(claimJobs(node, 100)).extracting(JobStore.ClaimedJob::id).contains(jobId);
    return jobId;
  }
}
