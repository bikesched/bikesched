// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL container of its own per test class, which the test may stop, start and restart.
 * Everything connects through a {@link TcpProxy}, never to the container's port directly: a
 * restarted container may get a new port, the proxies follow it.
 */
final class StressDatabase implements AutoCloseable {

  private final PostgreSQLContainer container;
  private final List<TcpProxy> proxies = new ArrayList<>();
  private final List<HikariDataSource> pools = new ArrayList<>();
  private final TcpProxy proxy;
  private volatile InetSocketAddress address;

  StressDatabase() {
    container = new PostgreSQLContainer("postgres:18");
    container.start();
    address = new InetSocketAddress(container.getHost(), container.getMappedPort(5432));
    proxy = newProxy();
  }

  /** A data source for the test's own reads and writes, one connection per call. */
  DataSource dataSource() {
    return dataSource(proxy);
  }

  /** A data source connecting through the given proxy, one connection per call. */
  DataSource dataSource(TcpProxy via) {
    var dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl(via));
    dataSource.setUser(container.getUsername());
    dataSource.setPassword(container.getPassword());
    return dataSource;
  }

  /**
   * A HikariCP pool through the shared proxy, as applications use it: fails fast while the database
   * is down (connection timeout of 2 seconds) and replaces broken connections.
   */
  HikariDataSource pool(String name, int size) {
    return pool(name, size, proxy, null);
  }

  /**
   * Like {@link #pool(String, int)}, through the given proxy and with the driver's {@code
   * socketTimeout} (seconds) if not {@code null}.
   */
  HikariDataSource pool(String name, int size, TcpProxy via, Integer socketTimeoutSeconds) {
    var config = new HikariConfig();
    config.setPoolName(name);
    config.setJdbcUrl(jdbcUrl(via));
    config.setUsername(container.getUsername());
    config.setPassword(container.getPassword());
    config.setMaximumPoolSize(size);
    config.setConnectionTimeout(2_000);
    config.setValidationTimeout(1_000);
    // Starts without a database, like a node started during an outage.
    config.setInitializationFailTimeout(-1);
    if (socketTimeoutSeconds != null) {
      config.addDataSourceProperty("socketTimeout", socketTimeoutSeconds.toString());
    }
    var pool = new HikariDataSource(config);
    synchronized (pools) {
      pools.add(pool);
    }
    return pool;
  }

  /** Closes the pools created so far, after their nodes were closed. */
  void closePools() {
    synchronized (pools) {
      pools.forEach(HikariDataSource::close);
      pools.clear();
    }
  }

  /** A new proxy to the database, e.g. for one node whose network a test breaks. */
  TcpProxy newProxy() {
    var newProxy = new TcpProxy(() -> address);
    synchronized (proxies) {
      proxies.add(newProxy);
    }
    return newProxy;
  }

  /** The JDBC URL of the database through the given proxy. */
  String jdbcUrl(TcpProxy via) {
    return "jdbc:postgresql://127.0.0.1:" + via.port() + "/" + container.getDatabaseName();
  }

  String username() {
    return container.getUsername();
  }

  String password() {
    return container.getPassword();
  }

  /** Stops the database container (a clean shutdown, aborting open transactions). */
  void stop() {
    container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(5).exec();
  }

  /** Starts the stopped database container and waits until it accepts connections. */
  void start() {
    container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
    address = inspectAddress();
    await("database accepting connections")
        .atMost(Duration.ofSeconds(60))
        .ignoreExceptions()
        .until(this::accepting);
  }

  /** Stops and starts the database container. */
  void restart() {
    stop();
    start();
  }

  /** Runs a statement on the database. */
  void execute(String sql) throws SQLException {
    try (var connection = dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** Runs a query returning one number. */
  long queryLong(String sql) throws SQLException {
    try (var connection = dataSource().getConnection();
        var statement = connection.createStatement();
        var rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** The step runs the job recorded in {@code stress_effects}, in order. */
  List<String> effects(UUID jobId) throws SQLException {
    List<String> steps = new ArrayList<>();
    try (var connection = dataSource().getConnection();
        var select =
            connection.prepareStatement(
                "select step from stress_effects where job_id = ? order by at")) {
      select.setObject(1, jobId);
      try (var rs = select.executeQuery()) {
        while (rs.next()) {
          steps.add(rs.getString(1));
        }
      }
    }
    return steps;
  }

  /** The job's state. */
  String state(UUID jobId) throws SQLException {
    try (var connection = dataSource().getConnection();
        var select = connection.prepareStatement("select state from bikesched_jobs where id = ?")) {
      select.setObject(1, jobId);
      try (var rs = select.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private boolean accepting() throws SQLException {
    try (var connection = dataSource().getConnection()) {
      return connection.isValid(1);
    }
  }

  private InetSocketAddress inspectAddress() {
    var bindings =
        container
            .getDockerClient()
            .inspectContainerCmd(container.getContainerId())
            .exec()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(ExposedPort.tcp(5432));
    return new InetSocketAddress(
        container.getHost(), Integer.parseInt(bindings[0].getHostPortSpec()));
  }

  @Override
  public void close() {
    synchronized (pools) {
      pools.forEach(HikariDataSource::close);
    }
    synchronized (proxies) {
      proxies.forEach(TcpProxy::close);
    }
    container.stop();
  }
}
