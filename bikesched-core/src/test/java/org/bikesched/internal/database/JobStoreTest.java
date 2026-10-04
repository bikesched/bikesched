package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    assertThat(database.jobs().claimJobs(node, 10))
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

    assertThat(database.jobs().claimJobs(node, 10))
        .singleElement()
        .satisfies(job -> assertThat(job.timeout()).isNull());
    assertThatThrownBy(() -> execute("update bikesched_jobs set timeout_millis = 0"))
        .as("timeouts must be positive")
        .hasMessageContaining("bikesched_jobs_timeout_check");
  }

  @Test
  void claimJobsClaimsJobsOfAnyType() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()"); // type 'test'
    execute("insert into bikesched_jobs (id, job_type) values (gen_random_uuid(), 'other')");

    assertThat(database.jobs().claimJobs(node, 10))
        .extracting(JobStore.ClaimedJob::type)
        .containsExactlyInAnyOrder("test", "other");
  }

  @Test
  void claimJobsClaimsAtMostLimitOfDueScheduledJobs() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(12, 0, "now() - interval '1 minute'");
    insertJobs(1, 0, "now() + interval '1 hour'"); // not due yet
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by) values "
            + "(gen_random_uuid(), 'test', 'RUNNING', '"
            + node
            + "')"); // already running

    List<JobStore.ClaimedJob> first = database.jobs().claimJobs(node, 10);
    List<JobStore.ClaimedJob> second = database.jobs().claimJobs(node, 10);

    assertThat(first).hasSize(10);
    assertThat(second).hasSize(2).doesNotContainAnyElementsOf(first);
    assertThat(database.jobs().claimJobs(node, 10)).isEmpty();
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

    assertThat(database.jobs().claimJobs(node, 2))
        .extracting(JobStore.ClaimedJob::priority)
        .containsExactly(5, 5);
    assertThat(database.jobs().claimJobs(node, 10))
        .extracting(JobStore.ClaimedJob::priority)
        .containsExactly(0, 0, 0);
  }

  @Test
  void completeJobMarksItSucceeded() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(1, 0, "now()");
    JobStore.ClaimedJob job = database.jobs().claimJobs(node, 10).getFirst();

    assertThat(database.jobs().completeJob(job.id(), otherNode)).isFalse();
    assertThat(database.jobs().completeJob(job.id(), node)).isTrue();
    assertThat(database.jobs().completeJob(job.id(), node)).isFalse();
    assertThat(count("state = 'SUCCEEDED' and locked_by is null and finished_at is not null"))
        .isEqualTo(1);
  }

  @Test
  void cancelJobMarksItCancelledWithMessage() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(2, 0, "now()");
    List<JobStore.ClaimedJob> jobs = database.jobs().claimJobs(node, 10);

    assertThat(database.jobs().cancelJob(jobs.get(0).id(), otherNode, "nope")).isFalse();
    assertThat(database.jobs().cancelJob(jobs.get(0).id(), node, "no longer needed")).isTrue();
    assertThat(database.jobs().cancelJob(jobs.get(0).id(), node, "again")).isFalse();
    assertThat(database.jobs().cancelJob(jobs.get(1).id(), node, null)).isTrue();

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
    List<JobStore.ClaimedJob> jobs = database.jobs().claimJobs(node, 10);

    var retried =
        database
            .jobs()
            .failJob(jobs.get(0).id(), node, "busy", 5, Duration.ofMinutes(10))
            .orElseThrow();
    assertThat(retried.state()).isEqualTo("SCHEDULED");
    assertThat(retried.maxAttempts()).isEqualTo(5);
    assertThat(retried.scheduledAt())
        .isBetween(Instant.now().plusSeconds(9 * 60), Instant.now().plusSeconds(11 * 60));
    assertThat(
            count(
                "id = '"
                    + jobs.get(0).id()
                    + "' and max_attempts = 5 and last_error = 'busy' and finished_at is null"))
        .isEqualTo(1);
    assertThat(database.jobs().claimJobs(node, 10)).as("not due before the delay").isEmpty();

    var failed =
        database
            .jobs()
            .failJob(jobs.get(1).id(), node, "give up", 1, Duration.ofMinutes(10))
            .orElseThrow();
    assertThat(failed.state()).as("no attempts left: retryAfter is ignored").isEqualTo("FAILED");
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

    JobStore.ClaimedJob first = database.jobs().claimJobs(node, 10).getFirst();
    assertThat(
            database
                .jobs()
                .failJob(first.id(), otherNode, "boom", null, null)
                .map(JobStore.FailedJob::state))
        .as("not claimed by that node")
        .isEmpty();
    assertThat(
            database
                .jobs()
                .failJob(first.id(), node, "boom 1", null, null)
                .map(JobStore.FailedJob::state))
        .contains("SCHEDULED");
    database.jobs().claimJobs(node, 10);
    assertThat(
            database
                .jobs()
                .failJob(first.id(), node, "boom 2", null, null)
                .map(JobStore.FailedJob::state))
        .contains("SCHEDULED");
    JobStore.ClaimedJob third = database.jobs().claimJobs(node, 10).getFirst();
    assertThat(third.attempts()).isEqualTo(3);
    assertThat(
            database
                .jobs()
                .failJob(first.id(), node, "boom 3", null, null)
                .map(JobStore.FailedJob::state))
        .contains("FAILED");

    assertThat(
            count(
                "state = 'FAILED' and attempts = 3 and last_error = 'boom 3' and locked_by is null "
                    + "and finished_at is not null"))
        .isEqualTo(1);
    assertThat(database.jobs().claimJobs(node, 10)).isEmpty();
  }

  @Test
  void releaseJobsReschedulesRunningJobsOfNode() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(5, 0, "now()");
    database.jobs().claimJobs(node, 3);
    database.jobs().claimJobs(otherNode, 1);

    assertThat(database.jobs().releaseJobs(node)).isEqualTo(3);
    assertThat(count("state = 'SCHEDULED' and locked_by is null")).isEqualTo(4);
    assertThat(count("state = 'RUNNING' and locked_by = '" + otherNode + "'")).isEqualTo(1);
    assertThat(database.nodes().deregisterNode(node)).isTrue();
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
