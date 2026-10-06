// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The lifecycle of the node: startup migration, polling and shutdown. */
class LifecycleTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void closingTheContextDeregistersTheNode() {
    String nodeName = unique("lifecycle-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.node-name=" + nodeName)
        .run(
            context -> {
              assertThat(context.getBean(Bikesched.class).node().isPolling()).isTrue();
              assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'")).isOne();
            });
    // The context is closed: the node is deregistered, its row removed.
    await()
        .untilAsserted(
            () -> assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'")).isZero());
  }

  @Test
  void pollingDisabledMigratesButDoesNotRegister() throws SQLException {
    String nodeName = unique("no-poll-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.node-name=" + nodeName, "bikesched.auto-start.enabled=false")
        .run(context -> assertThat(context.getBean(Bikesched.class).node().isPolling()).isFalse());
    assertThat(countRows("bikesched_nodes", "name = '" + nodeName + "'"))
        .as("no node was registered")
        .isZero();
    assertThat(tableExists("bikesched_jobs")).as("the schema was still migrated").isTrue();
  }

  @Test
  void migrateOnStartupFalseSkipsMigrationUntilFirstUse() {
    FreshDatabase fresh = FreshDatabase.start();
    runner()
        .withBean("dataSource", DataSource.class, fresh::dataSource)
        .withPropertyValues(
            "bikesched.node-name=" + unique("lazy-migrate-node"),
            "bikesched.migrate-on-startup=false",
            "bikesched.auto-start.enabled=false")
        .run(
            context -> {
              assertThat(fresh.tableExists("bikesched_jobs"))
                  .as("no migration at startup")
                  .isFalse();
              Bikesched node = context.getBean(Bikesched.class);
              node.registerJobHandler("lazy-migrate-type", _ -> {});
              node.newJob("lazy-migrate-type").schedule();
              assertThat(fresh.tableExists("bikesched_jobs"))
                  .as("the first database access migrates")
                  .isTrue();
            });
  }

  /** A throwaway database for the migration test, removed by Testcontainers on JVM exit. */
  private static final class FreshDatabase {

    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();

    private FreshDatabase() {}

    static FreshDatabase start() {
      FreshDatabase fresh = new FreshDatabase();
      fresh.postgres.start();
      fresh.dataSource.setUrl(fresh.postgres.getJdbcUrl());
      fresh.dataSource.setUser(fresh.postgres.getUsername());
      fresh.dataSource.setPassword(fresh.postgres.getPassword());
      return fresh;
    }

    DataSource dataSource() {
      return dataSource;
    }

    boolean tableExists(String table) throws SQLException {
      try (var connection = dataSource.getConnection();
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
  }
}
