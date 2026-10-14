// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

class JobStoreTest extends AbstractStoreTest {

  @Test
  void insertJobStoresScheduledJob() throws SQLException {
    UUID node = registerNode("node-1");
    UUID jobId = UUID.randomUUID();
    Instant scheduledAt =
        Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    database
        .jobs()
        .insertJob(
            jobId,
            new NewJob("mail", "{\"to\": \"alice\"}", scheduledAt, 7, 5, Duration.ofSeconds(90)));

    assertThat(
            count(
                "id = '"
                    + jobId
                    + "' and state = 'SCHEDULED' and attempts = 0 and locked_by is null "
                    + "and timeout_millis = 90000"))
        .isEqualTo(1);
    assertThat(claimJobs(node, 10))
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.id()).isEqualTo(jobId);
              assertThat(job.type()).isEqualTo("mail");
              assertThat(job.payload()).isEqualTo("{\"to\": \"alice\"}");
              assertThat(job.scheduledAt()).isEqualTo(scheduledAt);
              assertThat(job.priority()).isEqualTo(7);
              assertThat(job.maxAttempts()).isEqualTo(5);
              assertThat(job.timeout()).isEqualTo(Duration.ofSeconds(90));
            });
  }

  @Test
  void jobWithoutTimeoutHasNone() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");

    assertThat(claimJobs(node, 10))
        .singleElement()
        .satisfies(job -> assertThat(job.timeout()).isNull());
    assertThatThrownBy(() -> execute("update bikesched_jobs set timeout_millis = 0"))
        .as("timeouts must be positive")
        .hasMessageContaining("bikesched_jobs_timeout_check");
  }

  @Test
  void claimJobsClaimsOnlyJobsOfTheGivenTypes() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(2, 0, "now()"); // type 'test'
    execute("insert into bikesched_jobs (id, type, priority) values (gen_random_uuid(), 'new', 9)");

    assertThat(database.jobs().claimJobs(node, Set.of("test", "other"), 10))
        .extracting(JobStore.ClaimedJob::type)
        .containsExactly("test", "test");
    assertThat(count("type = 'new' and state = 'SCHEDULED' and attempts = 0"))
        .as("left to nodes with a handler")
        .isOne();
    assertThat(database.jobs().claimJobs(node, Set.of(), 10)).isEmpty();
  }

  @Test
  void releasingAJobWhoseFailureHandlerRunsPutsItBackToFailing() throws SQLException {
    UUID node = registerNode("node-failing");
    UUID jobId = UUID.randomUUID();
    execute(
        "insert into bikesched_jobs (id, type, state, failing_state, failure_attempts, locked_by,"
            + " lock_token, locked_at) values ('"
            + jobId
            + "', 'test', 'RUNNING', 'FAILED', 1, '"
            + node
            + "', gen_random_uuid(), now())");

    assertThat(database.jobs().releaseJobs(node)).isOne();

    assertThat(
            count(
                "id = '"
                    + jobId
                    + "' and state = 'FAILING' and failing_state = 'FAILED' and locked_by is null"))
        .isOne();
    assertThat(database.jobs().claimJobs(node, Set.of("test"), 10))
        .singleElement()
        .satisfies(
            claimed -> {
              assertThat(claimed.phase()).isEqualTo(new JobPhase.HandleFailure(JobState.FAILED, 2));
              assertThat(claimed.attempts()).isZero();
            });
  }

  @Test
  void retryJobSchedulesFailedJobsAgain() throws SQLException {
    UUID failed = UUID.randomUUID();
    UUID compensated = UUID.randomUUID();
    UUID occurrence = UUID.randomUUID();
    UUID succeeded = UUID.randomUUID();
    execute(
        "insert into bikesched_jobs (id, type, state, attempts, last_error, finished_at,"
            + " compensated_state, compensation_attempts, cancel_requested_at, recurrence) values"
            + " ('"
            + failed
            + "', 'test', 'FAILED', 3, 'boom', now(), null, 0, now(), null),"
            + " ('"
            + compensated
            + "', 'test', 'FAILED', 3, 'boom', now(), 'FAILED', 1, null, null),"
            + " ('"
            + occurrence
            + "', 'daily', 'FAILED', 3, 'boom', now(), null, 0, null, 'every PT1H'),"
            + " (gen_random_uuid(), 'daily', 'SCHEDULED', 0, null, null, null, 0, null, 'every PT1H'),"
            + " ('"
            + succeeded
            + "', 'test', 'SUCCEEDED', 1, null, now(), null, 0, null, null)");

    assertThat(database.jobs().retryJob(failed)).isTrue();
    assertThat(database.jobs().retryJob(compensated)).isTrue();
    assertThatThrownBy(() -> database.jobs().retryJob(occurrence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Job "
                + occurrence
                + " is an occurrence of recurring job daily and cannot be retried; its next"
                + " occurrence runs as scheduled");
    assertThat(database.jobs().retryJob(succeeded)).isFalse();
    assertThat(database.jobs().retryJob(failed)).as("already retried").isFalse();

    assertThat(
            count(
                "id in ('"
                    + failed
                    + "', '"
                    + compensated
                    + "')"
                    + " and state = 'SCHEDULED' and attempts = 0 and scheduled_at <= now()"
                    + " and finished_at is null and compensated_state is null"
                    + " and compensation_attempts = 0 and cancel_requested_at is null"
                    + " and last_error = 'boom'"))
        .isEqualTo(2);
    assertThat(count("id = '" + occurrence + "' and state = 'FAILED'")).isOne();
    assertThat(
            countRows(
                "bikesched_job_history",
                "job_id = '"
                    + failed
                    + "' and state = 'SCHEDULED' and attempt = 0"
                    + " and node_id is null and detail = 'retried'"))
        .isOne();
  }

  @Test
  void claimJobsClaimsAtMostLimitOfDueScheduledJobs() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(12, 0, "now() - interval '1 minute'");
    insertJobs(1, 0, "now() + interval '1 hour'"); // not due yet
    execute(
        "insert into bikesched_jobs (id, type, state, locked_by, lock_token) values "
            + "(gen_random_uuid(), 'test', 'RUNNING', '"
            + node
            + "', gen_random_uuid())"); // already running

    List<JobStore.ClaimedJob> first = claimJobs(node, 10);
    List<JobStore.ClaimedJob> second = claimJobs(node, 10);

    assertThat(first).hasSize(10);
    assertThat(second).hasSize(2).doesNotContainAnyElementsOf(first);
    assertThat(claimJobs(node, 10)).isEmpty();
    assertThat(first)
        .allSatisfy(
            job -> {
              assertThat(job.type()).isEqualTo("test");
              assertThat(job.attempts()).isEqualTo(1);
              assertThat(job.maxAttempts()).isEqualTo(3);
            });
    assertThat(count("state = 'RUNNING' and locked_by = '" + node + "'")).isEqualTo(13);
    assertThat(count("state = 'SCHEDULED'")).isEqualTo(1);
  }

  @Test
  void claimJobsReturnsHighestPriorityFirst() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(3, 0, "now()");
    insertJobs(2, 5, "now()");

    assertThat(claimJobs(node, 2)).extracting(JobStore.ClaimedJob::priority).containsExactly(5, 5);
    assertThat(claimJobs(node, 10))
        .extracting(JobStore.ClaimedJob::priority)
        .containsExactly(0, 0, 0);
  }

  @Test
  void completeJobMarksItSucceeded() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(1, 0, "now()");
    JobStore.ClaimedJob job = claimJobs(node, 10).getFirst();

    assertThat(database.jobs().completeJob(job.id(), staleLock(otherNode))).isFalse();
    assertThat(database.jobs().completeJob(job.id(), job.lock())).isTrue();
    assertThat(database.jobs().completeJob(job.id(), job.lock())).isFalse();
    assertThat(count("state = 'SUCCEEDED' and locked_by is null and finished_at is not null"))
        .isEqualTo(1);
  }

  @Test
  void cancelJobMarksItCancelledWithMessage() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(2, 0, "now()");
    List<JobStore.ClaimedJob> jobs = claimJobs(node, 10);

    assertThat(database.jobs().cancelJob(jobs.get(0).id(), staleLock(otherNode), "nope")).isFalse();
    assertThat(database.jobs().cancelJob(jobs.get(0).id(), jobs.get(0).lock(), "no longer needed"))
        .isTrue();
    assertThat(database.jobs().cancelJob(jobs.get(0).id(), jobs.get(0).lock(), "again")).isFalse();
    assertThat(database.jobs().cancelJob(jobs.get(1).id(), lock(jobs.get(1).id()), null)).isTrue();

    assertThat(
            count(
                "state = 'CANCELLED' and locked_by is null and finished_at is not null "
                    + "and last_error = 'no longer needed'"))
        .isEqualTo(1);
    assertThat(count("state = 'CANCELLED' and last_error is null")).isEqualTo(1);
  }

  @Test
  void failJobCanSetNewMaxAttemptsAndDelayTheRetry() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(2, 0, "now()");
    List<JobStore.ClaimedJob> jobs = claimJobs(node, 10);

    var retried =
        database
            .jobs()
            .failJob(jobs.get(0).id(), lock(jobs.get(0).id()), "busy", 5, Duration.ofMinutes(10))
            .orElseThrow();
    assertThat(retried.state()).isEqualTo(JobState.SCHEDULED);
    assertThat(retried.maxAttempts()).isEqualTo(5);
    assertThat(retried.scheduledAt())
        .isBetween(Instant.now().plusSeconds(9 * 60), Instant.now().plusSeconds(11 * 60));
    assertThat(
            count(
                "id = '"
                    + jobs.get(0).id()
                    + "' and max_attempts = 5 and last_error = 'busy' and finished_at is null"))
        .isEqualTo(1);
    assertThat(claimJobs(node, 10)).as("not due before the delay").isEmpty();

    var failed =
        database
            .jobs()
            .failJob(jobs.get(1).id(), lock(jobs.get(1).id()), "give up", 1, Duration.ofMinutes(10))
            .orElseThrow();
    assertThat(failed.state())
        .as("no attempts left: retryAfter is ignored")
        .isEqualTo(JobState.FAILED);
    assertThat(
            count(
                "id = '"
                    + jobs.get(1).id()
                    + "' and state = 'FAILED' and max_attempts = 1 and scheduled_at < now()"))
        .isEqualTo(1);
  }

  @Test
  void failJobRetriesUntilMaxAttemptsThenMarksFailed() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(1, 0, "now()");

    JobStore.ClaimedJob first = claimJobs(node, 10).getFirst();
    assertThat(
            database
                .jobs()
                .failJob(first.id(), staleLock(otherNode), "boom", null, null)
                .map(JobStore.FailedJob::state))
        .as("not claimed by that node")
        .isEmpty();
    assertThat(
            database
                .jobs()
                .failJob(first.id(), lock(first.id()), "boom 1", null, null)
                .map(JobStore.FailedJob::state))
        .contains(JobState.SCHEDULED);
    claimJobs(node, 10);
    assertThat(
            database
                .jobs()
                .failJob(first.id(), lock(first.id()), "boom 2", null, null)
                .map(JobStore.FailedJob::state))
        .contains(JobState.SCHEDULED);
    JobStore.ClaimedJob third = claimJobs(node, 10).getFirst();
    assertThat(third.attempts()).isEqualTo(3);
    assertThat(
            database
                .jobs()
                .failJob(first.id(), lock(first.id()), "boom 3", null, null)
                .map(JobStore.FailedJob::state))
        .contains(JobState.FAILED);

    assertThat(
            count(
                "state = 'FAILED' and attempts = 3 and last_error = 'boom 3' and locked_by is null "
                    + "and finished_at is not null"))
        .isEqualTo(1);
    assertThat(claimJobs(node, 10)).isEmpty();
  }

  @Test
  void releaseJobsReschedulesRunningJobsOfNode() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(5, 0, "now()");
    claimJobs(node, 3);
    claimJobs(otherNode, 1);

    assertThat(database.jobs().releaseJobs(node)).isEqualTo(3);
    assertThat(count("state = 'SCHEDULED' and locked_by is null")).isEqualTo(4);
    assertThat(count("state = 'RUNNING' and locked_by = '" + otherNode + "'")).isEqualTo(1);
    assertThat(database.nodes().deregisterNode(node)).isTrue();
  }

  @Test
  void releaseInactiveJobsLeavesActiveAndRecentlyClaimedJobsRunning() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(3, 0, "now()");
    List<JobStore.ClaimedJob> claimed = claimJobs(node, 3);
    UUID active = claimed.getFirst().id();
    UUID stale = claimed.get(1).id();
    UUID recent = claimed.get(2).id();
    execute(
        "update bikesched_jobs set locked_at = now() - interval '1 minute' where id in ('"
            + active
            + "', '"
            + stale
            + "')");

    assertThat(database.jobs().releaseInactiveJobs(node, Duration.ofSeconds(1), Set.of(active)))
        .isOne();
    assertThat(count("id = '" + stale + "' and state = 'SCHEDULED' and locked_by is null")).isOne();
    assertThat(count("id = '" + active + "' and state = 'RUNNING' and locked_by = '" + node + "'"))
        .as("active attempts are never released")
        .isOne();
    assertThat(count("id = '" + recent + "' and state = 'RUNNING' and locked_by = '" + node + "'"))
        .as("fresh claims get their grace period")
        .isOne();
  }

  @Test
  void releaseJobsOfDeadNodesOnlyTouchesNodesWithStaleHeartbeat() throws SQLException {
    UUID alive = registerNode("alive");
    UUID dead = registerNode("dead");
    UUID deadWithoutJobs = registerNode("dead-idle");
    UUID caller = registerNode("caller");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute' where id in ('"
            + dead
            + "', '"
            + deadWithoutJobs
            + "', '"
            + caller
            + "')");
    insertRunningJobs(alive, 2);
    insertRunningJobs(dead, 3);
    insertRunningJobs(caller, 1);

    assertThat(database.jobs().releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30)))
        .containsExactly(new JobStore.DeadNodeJobs(dead, "dead", 3));

    assertThat(count("state = 'SCHEDULED' and locked_by is null")).isEqualTo(3);
    assertThat(count("state = 'RUNNING' and locked_by = '" + alive + "'")).isEqualTo(2);
    assertThat(count("state = 'RUNNING' and locked_by = '" + caller + "'"))
        .as("the calling node never releases its own jobs")
        .isEqualTo(1);
    assertThat(database.jobs().releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30))).isEmpty();
    assertThat(database.jobs().releaseJobsOfDeadNodes(alive, Duration.ofSeconds(30)))
        .containsExactly(new JobStore.DeadNodeJobs(caller, "caller", 1));
  }
}
