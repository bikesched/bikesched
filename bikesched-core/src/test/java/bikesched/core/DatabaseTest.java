package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DatabaseTest {

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private static final Duration DEAD_AFTER = Duration.ofSeconds(15);

  static PGSimpleDataSource dataSource;
  static Database database;

  @BeforeAll
  static void migrate() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    database = new Database(dataSource, new JsonStringMapper());
    assertThat(database.migrate()).containsExactly(1);
  }

  @BeforeEach
  void deleteJobsAndNodes() throws SQLException {
    execute("delete from bikesched_jobs");
    execute("delete from bikesched_nodes");
  }

  @Test
  void migrateIsIdempotent() {
    assertThat(database.migrate()).isEmpty();
  }

  @Test
  void registersNewNodeWithGivenId() {
    UUID id = UUID.randomUUID();

    assertThat(database.registerNode(id, "node-1", DEAD_AFTER)).contains(id);
  }

  @Test
  void refusesNameHeldByLiveNode() {
    UUID first = UUID.randomUUID();
    database.registerNode(first, "node-1", DEAD_AFTER);

    assertThat(database.registerNode(UUID.randomUUID(), "node-1", DEAD_AFTER)).isEmpty();
  }

  @Test
  void takesOverNameHeldByDeadNode() throws SQLException {
    UUID dead = UUID.randomUUID();
    database.registerNode(dead, "node-1", DEAD_AFTER);
    execute("update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute'");

    assertThat(database.registerNode(UUID.randomUUID(), "node-1", DEAD_AFTER)).contains(dead);
    assertThat(lastHeartbeat(dead)).isAfter(OffsetDateTime.now().minusSeconds(10));
  }

  @Test
  void heartbeatUpdatesRegisteredNode() throws SQLException {
    UUID id = UUID.randomUUID();
    database.registerNode(id, "node-1", DEAD_AFTER);
    execute("update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute'");

    assertThat(database.heartbeat(id)).isTrue();
    assertThat(lastHeartbeat(id)).isAfter(OffsetDateTime.now().minusSeconds(10));
  }

  @Test
  void heartbeatOfUnknownNodeReturnsFalse() {
    assertThat(database.heartbeat(UUID.randomUUID())).isFalse();
  }

  @Test
  void deregisterRemovesNode() {
    UUID id = UUID.randomUUID();
    database.registerNode(id, "node-1", DEAD_AFTER);

    assertThat(database.deregisterNode(id)).isTrue();
    assertThat(database.heartbeat(id)).isFalse();
    assertThat(database.deregisterNode(id)).isFalse();
  }

  @Test
  void insertJobStoresScheduledJob() throws SQLException {
    UUID node = registerNode("node-1");
    UUID jobId = UUID.randomUUID();
    Instant scheduledAt =
        Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    database.insertJob(
        jobId,
        new NewJob("mail", "{\"to\": \"alice\"}", scheduledAt, 7, 5, Duration.ofSeconds(90)));

    assertThat(
            count(
                "id = '"
                    + jobId
                    + "' and state = 'SCHEDULED' and attempts = 0 and locked_by is null "
                    + "and timeout_millis = 90000"))
        .isEqualTo(1);
    assertThat(database.claimJobs(node, 10))
        .singleElement()
        .satisfies(
            job -> {
              assertThat(job.id()).isEqualTo(jobId);
              assertThat(job.type()).isEqualTo("mail");
              assertThat(job.payload()).isEqualTo("{\"to\": \"alice\"}");
              assertThat(job.scheduledAt()).isEqualTo(scheduledAt);
              assertThat(job.priority()).isEqualTo(7);
              assertThat(job.maxAttempts()).isEqualTo(5);
              assertThat(job.timeout()).contains(Duration.ofSeconds(90));
            });
  }

  @Test
  void jobWithoutTimeoutHasNone() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");

    assertThat(database.claimJobs(node, 10))
        .singleElement()
        .satisfies(job -> assertThat(job.timeout()).isEmpty());
    assertThatThrownBy(() -> execute("update bikesched_jobs set timeout_millis = 0"))
        .as("timeouts must be positive")
        .hasMessageContaining("bikesched_jobs_timeout_check");
  }

  @Test
  void claimJobsClaimsJobsOfAnyType() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()"); // type 'test'
    execute("insert into bikesched_jobs (id, job_type) values (gen_random_uuid(), 'other')");

    assertThat(database.claimJobs(node, 10))
        .extracting(Job::type)
        .containsExactlyInAnyOrder("test", "other");
  }

  @Test
  void failJobCanSetNewMaxAttemptsAndDelayTheRetry() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(2, 0, "now()");
    List<DefaultJob> jobs = database.claimJobs(node, 10);

    var retried =
        database.failJob(jobs.get(0).id(), node, "busy", 5, Duration.ofMinutes(10)).orElseThrow();
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
    assertThat(database.claimJobs(node, 10)).as("not due before the delay").isEmpty();

    var failed =
        database
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

    Job first = database.claimJobs(node, 10).getFirst();
    assertThat(
            database
                .failJob(first.id(), otherNode, "boom", null, null)
                .map(Database.FailedJob::state))
        .as("not claimed by that node")
        .isEmpty();
    assertThat(
            database.failJob(first.id(), node, "boom 1", null, null).map(Database.FailedJob::state))
        .contains("SCHEDULED");
    database.claimJobs(node, 10);
    assertThat(
            database.failJob(first.id(), node, "boom 2", null, null).map(Database.FailedJob::state))
        .contains("SCHEDULED");
    Job third = database.claimJobs(node, 10).getFirst();
    assertThat(third.attempts()).isEqualTo(3);
    assertThat(
            database.failJob(first.id(), node, "boom 3", null, null).map(Database.FailedJob::state))
        .contains("FAILED");

    assertThat(
            count(
                "state = 'FAILED' and attempts = 3 and last_error = 'boom 3' and locked_by is null "
                    + "and finished_at is not null"))
        .isEqualTo(1);
    assertThat(database.claimJobs(node, 10)).isEmpty();
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

    List<DefaultJob> first = database.claimJobs(node, 10);
    List<DefaultJob> second = database.claimJobs(node, 10);

    assertThat(first).hasSize(10);
    assertThat(second).hasSize(2).doesNotContainAnyElementsOf(first);
    assertThat(database.claimJobs(node, 10)).isEmpty();
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

    assertThat(database.claimJobs(node, 2)).extracting(DefaultJob::priority).containsExactly(5, 5);
    assertThat(database.claimJobs(node, 10))
        .extracting(DefaultJob::priority)
        .containsExactly(0, 0, 0);
  }

  @Test
  void redactUrlRemovesCredentials() {
    assertThat(Database.redactUrl("jdbc:postgresql://localhost:5432/bikesched"))
        .isEqualTo("jdbc:postgresql://localhost:5432/bikesched");
    assertThat(
            Database.redactUrl(
                "jdbc:postgresql://localhost:5432/bikesched?user=bob&password=secret&ssl=true"))
        .isEqualTo("jdbc:postgresql://localhost:5432/bikesched");
    assertThat(
            Database.redactUrl(
                "jdbc:postgresql://bob:secret@db.example.com:5432/bikesched?sslmode=require"))
        .isEqualTo("jdbc:postgresql://db.example.com:5432/bikesched");
    assertThat(
            Database.redactUrl("jdbc:postgresql://host1:5432,host2:5433/bikesched?password=secret"))
        .isEqualTo("jdbc:postgresql://host1:5432,host2:5433/bikesched");
    assertThat(Database.redactUrl("jdbc:sqlserver://localhost:1433;user=bob;password=secret"))
        .isEqualTo("jdbc:sqlserver://localhost:1433");
    assertThat(Database.redactUrl(null)).isEqualTo("<unknown>");
  }

  @Test
  void describeReturnsRedactedUrlAndVersion() {
    assertThat(database.describe())
        .matches("jdbc:postgresql://[^/@?]+:\\d+/\\w+ \\(PostgreSQL 18\\..*\\)")
        .doesNotContain("password");
  }

  @Test
  void stepLifecycleStoresResultAndCountsAttempts() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = database.claimJobs(node, 1).getFirst().id();

    assertThat(database.findCompletedStep(jobId, "a")).isEmpty();
    assertThat(database.startStep(jobId, "a")).contains(1);
    database.failStep(jobId, "a", "boom");
    assertThat(database.findCompletedStep(jobId, "a")).as("failed is not completed").isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'FAILED' and last_error = 'boom' and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.startStep(jobId, "a")).as("second attempt").contains(2);
    assertThat(countSteps("name = 'a' and state = 'RUNNING' and finished_at is null")).isEqualTo(1);
    database.completeStep(jobId, "a", "{\"x\": 1}");

    assertThat(database.findCompletedStep(jobId, "a"))
        .contains(new Database.CompletedStep("{\"x\": 1}"));
    assertThat(database.startStep(jobId, "a")).as("no restart once succeeded").isEmpty();
    assertThat(
            countSteps(
                "name = 'a' and state = 'SUCCEEDED' and attempts = 2 and finished_at is not null"))
        .isEqualTo(1);

    assertThat(database.startStep(jobId, "b")).contains(1);
    database.completeStep(jobId, "b", null);
    assertThat(database.findCompletedStep(jobId, "b")).contains(new Database.CompletedStep(null));
  }

  @Test
  void completeStepRejectsInvalidJson() throws SQLException {
    UUID node = registerNode("node-1");
    insertJobs(1, 0, "now()");
    UUID jobId = database.claimJobs(node, 1).getFirst().id();
    database.startStep(jobId, "a");

    assertThatThrownBy(() -> database.completeStep(jobId, "a", "not json"))
        .isInstanceOf(BikeschedException.class)
        .hasMessage("Storing the result of step 'a' of bikesched job " + jobId + " failed");
    assertThat(countSteps("name = 'a' and state = 'RUNNING'")).isEqualTo(1);
  }

  @Test
  void completeJobMarksItSucceeded() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(1, 0, "now()");
    Job job = database.claimJobs(node, 10).getFirst();

    assertThat(database.completeJob(job.id(), otherNode)).isFalse();
    assertThat(database.completeJob(job.id(), node)).isTrue();
    assertThat(database.completeJob(job.id(), node)).isFalse();
    assertThat(count("state = 'SUCCEEDED' and locked_by is null and finished_at is not null"))
        .isEqualTo(1);
  }

  @Test
  void cancelJobMarksItCancelledWithMessage() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(2, 0, "now()");
    List<DefaultJob> jobs = database.claimJobs(node, 10);

    assertThat(database.cancelJob(jobs.get(0).id(), otherNode, "nope")).isFalse();
    assertThat(database.cancelJob(jobs.get(0).id(), node, "no longer needed")).isTrue();
    assertThat(database.cancelJob(jobs.get(0).id(), node, "again")).isFalse();
    assertThat(database.cancelJob(jobs.get(1).id(), node, null)).isTrue();

    assertThat(
            count(
                "state = 'CANCELLED' and locked_by is null and finished_at is not null "
                    + "and last_error = 'no longer needed'"))
        .isEqualTo(1);
    assertThat(count("state = 'CANCELLED' and last_error is null")).isEqualTo(1);
  }

  @Test
  void releaseJobsReschedulesRunningJobsOfNode() throws SQLException {
    UUID node = registerNode("node-1");
    UUID otherNode = registerNode("node-2");
    insertJobs(5, 0, "now()");
    database.claimJobs(node, 3);
    database.claimJobs(otherNode, 1);

    assertThat(database.releaseJobs(node)).isEqualTo(3);
    assertThat(count("state = 'SCHEDULED' and locked_by is null")).isEqualTo(4);
    assertThat(count("state = 'RUNNING' and locked_by = '" + otherNode + "'")).isEqualTo(1);
    assertThat(database.deregisterNode(node)).isTrue();
  }

  @Test
  void deleteDeadNodesRemovesOnlyLongDeadNodesWithoutRunningJobs() throws SQLException {
    UUID alive = registerNode("alive");
    UUID recentlyDead = registerNode("recently-dead");
    UUID longDead = registerNode("long-dead");
    UUID longDeadWithJob = registerNode("long-dead-busy");
    UUID caller = registerNode("caller");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '45 seconds' where id = '"
            + recentlyDead
            + "'");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '2 minutes' where id in ('"
            + longDead
            + "', '"
            + longDeadWithJob
            + "', '"
            + caller
            + "')");
    insertRunningJobs(longDeadWithJob, 1);

    assertThat(database.deleteDeadNodes(caller, Duration.ofSeconds(60)))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.nodeId()).isEqualTo(longDead);
              assertThat(node.nodeName()).isEqualTo("long-dead");
              assertThat(node.lastHeartbeatAt()).isBefore(Instant.now().minusSeconds(60));
            });

    assertThat(database.heartbeat(alive)).isTrue();
    assertThat(database.heartbeat(recentlyDead))
        .as("dead, but not for twice the timeout yet")
        .isTrue();
    assertThat(database.heartbeat(longDeadWithJob))
        .as("still referenced by a running job")
        .isTrue();
    assertThat(database.heartbeat(caller)).as("the calling node is never deleted").isTrue();
    assertThat(database.heartbeat(longDead)).isFalse();
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

    assertThat(database.releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30)))
        .containsExactly(new Database.DeadNodeJobs(dead, "dead", 3));

    assertThat(count("state = 'SCHEDULED' and locked_by is null")).isEqualTo(3);
    assertThat(count("state = 'RUNNING' and locked_by = '" + alive + "'")).isEqualTo(2);
    assertThat(count("state = 'RUNNING' and locked_by = '" + caller + "'"))
        .as("the calling node never releases its own jobs")
        .isEqualTo(1);
    assertThat(database.releaseJobsOfDeadNodes(caller, Duration.ofSeconds(30))).isEmpty();
    assertThat(database.releaseJobsOfDeadNodes(alive, Duration.ofSeconds(30)))
        .containsExactly(new Database.DeadNodeJobs(caller, "caller", 1));
  }

  private static void insertRunningJobs(UUID node, int count) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by, locked_at) select gen_random_uuid(), "
            + "'test', 'RUNNING', '"
            + node
            + "', now() from generate_series(1, "
            + count
            + ")");
  }

  private static UUID registerNode(String name) {
    return database.registerNode(UUID.randomUUID(), name, DEAD_AFTER).orElseThrow();
  }

  private static void insertJobs(int count, int priority, String scheduledAt) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, job_type, priority, scheduled_at) select gen_random_uuid(), 'test', "
            + priority
            + ", "
            + scheduledAt
            + " from generate_series(1, "
            + count
            + ")");
  }

  private static long count(String where) throws SQLException {
    return countRows("bikesched_jobs", where);
  }

  private static long countSteps(String where) throws SQLException {
    return countRows("bikesched_steps", where);
  }

  private static long countRows(String table, String where) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " where " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void execute(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static OffsetDateTime lastHeartbeat(UUID nodeId) throws SQLException {
    try (var connection = dataSource.getConnection();
        var select =
            connection.prepareStatement(
                "select last_heartbeat_at from bikesched_nodes where id = ?")) {
      select.setObject(1, nodeId);
      try (var rs = select.executeQuery()) {
        rs.next();
        return rs.getObject(1, OffsetDateTime.class);
      }
    }
  }
}
