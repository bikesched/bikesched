// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bikesched.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the store tests: one migrated PostgreSQL database of their own (started once per
 * JVM), emptied before each test, plus SQL helpers.
 */
abstract class AbstractStoreTest {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  static final Duration DEAD_AFTER = Duration.ofSeconds(15);

  /**
   * Passes JSON text through, like bikesched's default mapper (which lives in {@code internal}).
   */
  static final JsonMapper JSON_TEXT =
      new JsonMapper() {
        @Override
        public String toJson(Object value) {
          return (String) value;
        }

        @Override
        public <T> T fromJson(String json, Class<T> type) {
          return type.cast(json);
        }
      };

  static final PGSimpleDataSource dataSource;
  static final Database database;

  static {
    POSTGRES.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    // With the job history, so the store tests also cover recording it in the stores' transactions.
    database = new Database(dataSource, JSON_TEXT, true);
    assertThat(database.migrate()).containsExactly(1);
  }

  @BeforeEach
  void deleteAll() throws SQLException {
    execute("delete from bikesched_job_history");
    execute("delete from bikesched_events");
    execute("delete from bikesched_jobs");
    execute("delete from bikesched_nodes");
  }

  /** Claims due jobs of every type in the table, as a node with handlers for all of them would. */
  static List<JobStore.ClaimedJob> claimJobs(UUID node, int limit) {
    List<String> types = new ArrayList<>();
    try (var connection = dataSource.getConnection();
        var select = connection.prepareStatement("select distinct job_type from bikesched_jobs");
        var rows = select.executeQuery()) {
      while (rows.next()) {
        types.add(rows.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return database.jobs().claimJobs(node, types, limit);
  }

  static void insertRunningJobs(UUID node, int count) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by, lock_token, locked_at) select"
            + " gen_random_uuid(), 'test', 'RUNNING', '"
            + node
            + "', gen_random_uuid(), now() from generate_series(1, "
            + count
            + ")");
  }

  /**
   * The lock of the job's current claim, as the claiming node holds it.
   *
   * @throws IllegalStateException if the job is not running
   */
  static JobLock lock(UUID jobId) {
    try (var connection = dataSource.getConnection();
        var select =
            connection.prepareStatement(
                "select locked_by, lock_token from bikesched_jobs"
                    + " where id = ? and state = 'RUNNING'")) {
      select.setObject(1, jobId);
      try (var rs = select.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("job " + jobId + " is not running");
        }
        return new JobLock(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A lock of the node that does not match any claim, e.g. of a claim that was released. */
  static JobLock staleLock(UUID nodeId) {
    return new JobLock(nodeId, UUID.randomUUID());
  }

  static UUID registerNode(String name) {
    return database.nodes().registerNode(UUID.randomUUID(), name, DEAD_AFTER).orElseThrow();
  }

  static void insertJobs(int count, int priority, String scheduledAt) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, job_type, priority, scheduled_at) select gen_random_uuid(), 'test', "
            + priority
            + ", "
            + scheduledAt
            + " from generate_series(1, "
            + count
            + ")");
  }

  static long count(String where) throws SQLException {
    return countRows("bikesched_jobs", where);
  }

  static long countSteps(String where) throws SQLException {
    return countRows("bikesched_steps", where);
  }

  static long countRows(String table, String where) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " where " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  static void execute(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static OffsetDateTime lastHeartbeat(UUID nodeId) throws SQLException {
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
