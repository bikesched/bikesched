// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the {@link Bikesched} integration tests of all packages: one PostgreSQL container
 * shared by all test classes of the JVM (started once, stopped by Testcontainers on JVM exit) plus
 * SQL helpers. Tests isolate themselves by unique node names and job names; test classes run
 * sequentially.
 */
public abstract class AbstractBikeschedTest {

  /** The shared database container. */
  public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  /** A data source for the shared database. */
  public static final PGSimpleDataSource dataSource;

  static {
    POSTGRES.start();
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    // Defaults of await(): the nodes of the tests poll every 50 ms.
    Awaitility.setDefaultTimeout(Duration.ofSeconds(10));
    Awaitility.setDefaultPollInterval(Duration.ofMillis(50));
    Awaitility.setDefaultPollDelay(Duration.ZERO);
  }

  /**
   * A builder for a test node on the shared database polling every 50 ms; also gives tests of other
   * packages access to the package-private test hooks of {@link BikeschedBuilder}.
   */
  public static BikeschedBuilder testNode(String nodeName) {
    return Bikesched.builder()
        .dataSource(dataSource)
        .nodeName(nodeName)
        .pollInterval(Duration.ofMillis(50));
  }

  /** Like {@link #testNode(String)}, with the given heartbeat interval. */
  public static BikeschedBuilder testNode(String nodeName, Duration heartbeatInterval) {
    return testNode(nodeName).heartbeatInterval(heartbeatInterval);
  }

  /** Waits until the job is in the given state. */
  public static void awaitState(UUID jobId, String state) {
    await()
        .untilAsserted(
            () ->
                assertThat(count("id = '" + jobId + "' and state = '" + state + "'"))
                    .as("job in state " + state)
                    .isEqualTo(1));
  }

  public static void execute(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  public static long count(String where) throws SQLException {
    return countRows("bikesched_jobs", where);
  }

  public static long countRows(String table, String where) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " where " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  public static String nodeName(UUID nodeId) throws SQLException {
    try (var connection = dataSource.getConnection();
        var select = connection.prepareStatement("select name from bikesched_nodes where id = ?")) {
      select.setObject(1, nodeId);
      try (var rs = select.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  public static OffsetDateTime lastHeartbeat(UUID nodeId) throws SQLException {
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
