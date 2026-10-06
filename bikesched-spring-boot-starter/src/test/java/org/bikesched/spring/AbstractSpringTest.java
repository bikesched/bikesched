// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the Spring module's integration tests: one PostgreSQL container shared by all test
 * classes of the JVM, plus SQL helpers. Tests isolate themselves by unique node names and job
 * types.
 */
public abstract class AbstractSpringTest {

  /** The shared database container. */
  public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  /** A data source for the shared database. */
  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
    // Defaults of await(): the nodes of the tests poll every 50 ms (set per node in the tests).
    Awaitility.setDefaultTimeout(Duration.ofSeconds(10));
    Awaitility.setDefaultPollInterval(Duration.ofMillis(50));
    Awaitility.setDefaultPollDelay(Duration.ZERO);
  }

  /** Returns the data source of the shared test database. */
  protected static DataSource dataSource() {
    return DATA_SOURCE;
  }

  /** Counts the rows of a table matching the (already-escaped) where clause. */
  protected static long countRows(String table, String where) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery("select count(*) from " + table + " where " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** Whether a table with the given name exists. */
  protected static boolean tableExists(String table) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                "select count(*) from information_schema.tables where table_name = '"
                    + table
                    + "'")) {
      rs.next();
      return rs.getLong(1) > 0;
    }
  }

  /** Whether any exception in the cause chain carries the given message fragment. */
  protected static boolean anyCauseMessage(Throwable failure, String fragment) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}
