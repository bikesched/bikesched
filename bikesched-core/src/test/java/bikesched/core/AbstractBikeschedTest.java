package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the {@link Bikesched} integration tests: one PostgreSQL container shared by all
 * test classes of the JVM (started once, stopped by Testcontainers on JVM exit) plus SQL helpers.
 * Tests isolate themselves by unique node names and job types; test classes run sequentially.
 */
abstract class AbstractBikeschedTest {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  static final PGSimpleDataSource dataSource;

  static {
    POSTGRES.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
  }

  static void awaitState(UUID jobId, String state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (count("id = '" + jobId + "' and state = '" + state + "'") == 0
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(count("id = '" + jobId + "' and state = '" + state + "'"))
        .as("job in state " + state)
        .isEqualTo(1);
  }

  static void execute(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static long count(String where) throws SQLException {
    return countRows("bikesched_jobs", where);
  }

  static long countRows(String table, String where) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " where " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  static String nodeName(UUID nodeId) throws SQLException {
    try (var connection = dataSource.getConnection();
        var select = connection.prepareStatement("select name from bikesched_nodes where id = ?")) {
      select.setObject(1, nodeId);
      try (var rs = select.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
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
