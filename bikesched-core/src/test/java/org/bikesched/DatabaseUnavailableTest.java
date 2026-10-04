package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bikesched.internal.database.DatabaseInitializer;
import org.junit.jupiter.api.Test;

/**
 * Building a node while the database is unavailable: without auto-polling, {@code build()} does not
 * access the database; it is initialized on first use, and again on the next use if that failed.
 */
class DatabaseUnavailableTest extends AbstractBikeschedTest {

  @Test
  void nodeWithoutPollingCanBeBuiltWhileTheDatabaseIsDown() throws Exception {
    var database = new SwitchableDataSource(false);
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(database)
            .nodeName("node-database-down")
            .autoStartPolling(false)
            .build()) {
      assertThatThrownBy(bikesched::startPolling)
          .as("the database is still down")
          .isInstanceOf(BikeschedException.class);
      assertThat(bikesched.node().isPolling()).isFalse();
      assertThatThrownBy(() -> bikesched.newJob("database-down").handler(job -> {}).schedule())
          .as("scheduling needs the database, too")
          .isInstanceOf(BikeschedException.class);

      database.available = true;
      bikesched.newJob("database-down").handler(job -> {}).schedule(); // initializes on retry
      Node node = bikesched.startPolling();

      assertThat(node.isPolling()).isTrue();
      assertThat(nodeName(node.id().orElseThrow())).isEqualTo("node-database-down");
    }
  }

  @Test
  void nodeWithPollingCannotBeBuiltWhileTheDatabaseIsDown() {
    var database = new SwitchableDataSource(false);

    assertThatThrownBy(
            () -> Bikesched.builder().dataSource(database).nodeName("node-polling-down").build())
        .isInstanceOf(BikeschedException.class);
  }

  @Test
  void databaseIsInitializedOnFirstUseOnly() throws Exception {
    var database = new SwitchableDataSource(true);
    var logs = LogCapture.of(DatabaseInitializer.class);
    try (logs;
        Bikesched bikesched =
            Bikesched.builder()
                .dataSource(database)
                .nodeName("node-lazy-initialization")
                .autoStartPolling(false)
                .build()) {
      assertThat(database.connections.get()).as("build() does not access the database").isZero();

      bikesched.newJob("lazy-initialization").handler(job -> {}).schedule();
      bikesched.newJob("lazy-initialization").handler(job -> {}).schedule();

      assertThat(logs.messages())
          .filteredOn(message -> message.startsWith("Database: "))
          .as("initialized once, on the first scheduled job")
          .hasSize(1);
    }
    execute(
        "delete from bikesched_jobs where job_type in ('database-down', 'lazy-initialization')");
  }

  /** The test database, unless switched off; counts the connections requested. */
  private static final class SwitchableDataSource implements DataSource {

    final AtomicInteger connections = new AtomicInteger();
    volatile boolean available;

    SwitchableDataSource(boolean available) {
      this.available = available;
    }

    @Override
    public Connection getConnection() throws SQLException {
      connections.incrementAndGet();
      if (!available) {
        throw new SQLException("Connection refused (test database is down)", "08001");
      }
      return dataSource.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
