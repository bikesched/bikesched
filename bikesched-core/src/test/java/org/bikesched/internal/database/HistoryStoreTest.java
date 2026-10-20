// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.bikesched.job.JobHistoryEntry;
import org.junit.jupiter.api.Test;

/**
 * The {@link HistoryStore} and the changes the other stores record in it, for the paths the
 * lifecycle tests in {@code JobHistoryTest} do not reach. The store tests run with the history
 * enabled (see {@link AbstractStoreTest}).
 */
class HistoryStoreTest extends AbstractStoreTest {

  @Test
  void jobsReleasedFromADeadNodeAreRecordedForTheReleasingNode() throws SQLException {
    UUID dead = registerNode("dead");
    UUID caller = registerNode("caller");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute' where id = '"
            + dead
            + "'");
    UUID jobId = scheduleAndClaim(dead);

    database.jobs().releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30));

    JobHistoryEntry released = database.history().findHistory(jobId).getLast();
    assertThat(released.state()).isEqualTo("SCHEDULED");
    assertThat(released.attempt()).isEqualTo(1);
    assertThat(released.nodeId()).isEqualTo(caller);
    assertThat(released.detail()).isEqualTo("released from dead node dead");
  }

  @Test
  void failedStepAndTimedOutWaitAreRecordedForTheRunningNode() throws Exception {
    UUID node = registerNode("node-steps");
    UUID jobId = scheduleAndClaim(node);

    database.steps().startStep(jobId, lock(jobId), "charge");
    database.steps().failStep(jobId, lock(jobId), "charge", "card declined");
    database.steps().startStep(jobId, lock(jobId), "charge");
    database
        .events()
        .awaitEvent(jobId, lock(jobId), "wait", "paid", "order-1", Duration.ofMillis(1));
    Thread.sleep(20);
    database
        .events()
        .awaitEvent(jobId, lock(jobId), "wait", "paid", "order-1", Duration.ofMillis(1));

    List<JobHistoryEntry> history = database.history().findHistory(jobId);
    assertThat(history.subList(2, history.size()))
        .extracting(e -> e.stepName() + ":" + e.state() + ":" + e.detail())
        .containsExactly(
            "charge:RUNNING:null",
            "charge:FAILED:card declined",
            "charge:RUNNING:step attempt 2",
            "wait:WAITING:waiting for event paid/order-1 until " + waitUntil(jobId, "wait"),
            "wait:TIMED_OUT:null");
    // Step changes take the job's attempt and the node running it.
    assertThat(history.subList(2, history.size()))
        .allSatisfy(
            entry -> {
              assertThat(entry.attempt()).isEqualTo(1);
              assertThat(entry.nodeId()).isEqualTo(node);
            });
  }

  @Test
  void deletedOccurrenceIsRecorded() {
    var registration =
        database
            .jobs()
            .registerRecurringJob(
                new NewJob(
                    "report",
                    null,
                    Instant.now().plusSeconds(3600),
                    0,
                    3,
                    null,
                    "every PT1H",
                    null));

    assertThat(database.jobs().deletePendingOccurrences("report")).isEqualTo(1);

    assertThat(database.history().findHistory(registration.jobId()))
        .extracting(JobHistoryEntry::state, JobHistoryEntry::detail)
        .last()
        .isEqualTo(tuple("SCHEDULED", "deleted: recurring job removed"));
  }

  @Test
  void entriesOlderThanTheRetentionAreDeleted() throws SQLException {
    UUID jobId = UUID.randomUUID();
    execute(
        "insert into bikesched_job_history (job_id, created_at, state, attempt) values ('"
            + jobId
            + "', now() - interval '2 days', 'SCHEDULED', 0), ('"
            + jobId
            + "', now(), 'RUNNING', 1)");

    assertThat(database.history().deleteHistory(null, null, Duration.ofDays(1), 1000).count())
        .isEqualTo(1);

    assertThat(database.history().findHistory(jobId))
        .extracting(JobHistoryEntry::state)
        .containsExactly("RUNNING");
    assertThat(database.history().findHistory(UUID.randomUUID())).isEmpty();
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

  private static Instant waitUntil(UUID jobId, String stepName) throws SQLException {
    try (var connection = dataSource.getConnection();
        var query =
            connection.prepareStatement(
                "select wait_until from bikesched_steps where job_id = ? and name = ?")) {
      query.setObject(1, jobId);
      query.setString(2, stepName);
      try (var rs = query.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1, OffsetDateTime.class).toInstant();
      }
    }
  }
}
