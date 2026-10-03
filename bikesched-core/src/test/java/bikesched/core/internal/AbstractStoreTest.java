package bikesched.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import bikesched.core.JsonMapper;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
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

  static final PGSimpleDataSource dataSource;
  static final Database database;

  static {
    POSTGRES.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    database = new Database(dataSource, JsonMapper.rawJson());
    assertThat(database.migrate()).containsExactly(1);
  }

  @BeforeEach
  void deleteAll() throws SQLException {
    execute("delete from bikesched_events");
    execute("delete from bikesched_jobs");
    execute("delete from bikesched_nodes");
  }

  static void insertRunningJobs(UUID node, int count) throws SQLException {
    execute(
        "insert into bikesched_jobs (id, job_type, state, locked_by, locked_at) select gen_random_uuid(), "
            + "'test', 'RUNNING', '"
            + node
            + "', now() from generate_series(1, "
            + count
            + ")");
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
