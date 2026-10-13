// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Property binding and the independent automatic and explicit migration paths. */
class SchemaMigrationPropertiesTest extends AbstractSpringTest {

  @Test
  void disabledMigrationValidatesOnFirstUseWithoutCreatingTables() throws SQLException {
    try (var database = new IsolatedDatabase()) {
      runner(database)
          .withPropertyValues("bikesched.migrate-schema=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var node = context.getBean(Bikesched.class);
                assertThatThrownBy(() -> node.findJob(UUID.randomUUID()))
                    .isInstanceOf(BikeschedException.class)
                    .hasMessageContaining("Bikesched.migrate()");
                assertThat(database.hasHistory()).isFalse();
              });
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {true})
  void defaultAndEnabledMigrationAreLazyWithoutPolling(Boolean migrateSchema) throws SQLException {
    try (var database = new IsolatedDatabase()) {
      var runner = runner(database);
      if (migrateSchema != null) {
        runner = runner.withPropertyValues("bikesched.migrate-schema=" + migrateSchema);
      }
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BikeschedProperties.class).migrateSchema())
                .isEqualTo(migrateSchema);
            assertThat(database.hasHistory()).isFalse();
            var node = context.getBean(Bikesched.class);
            assertThat(node.findJob(UUID.randomUUID())).isEmpty();
            assertThat(database.hasHistory()).isTrue();
            assertThat(node.node().id()).isEmpty();
          });
    }
  }

  @Test
  void defaultPollingMigratesAtStartup() throws SQLException {
    try (var database = new IsolatedDatabase()) {
      runner(database)
          .withPropertyValues("bikesched.auto-start.enabled=true")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(database.hasHistory()).isTrue();
                assertThat(context.getBean(Bikesched.class).node().isPolling()).isTrue();
              });
    }
  }

  @Test
  void pollingValidatesAtStartupAndFailsWithoutHistory() throws SQLException {
    try (var database = new IsolatedDatabase()) {
      runner(database)
          .withPropertyValues("bikesched.migrate-schema=false", "bikesched.auto-start.enabled=true")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(anyCauseMessage(context.getStartupFailure(), "Bikesched.migrate()"))
                    .isTrue();
                assertThat(database.hasHistory()).isFalse();
              });
    }
  }

  @Test
  void explicitMigrationRemainsAvailableWithAutomaticMigrationDisabled() throws SQLException {
    try (var database = new IsolatedDatabase()) {
      runner(database)
          .withPropertyValues("bikesched.migrate-schema=false")
          .run(
              context -> {
                assertThat(context.getBean(BikeschedProperties.class).migrateSchema()).isFalse();
                var node = context.getBean(Bikesched.class);
                assertThat(database.hasHistory()).isFalse();
                node.migrate();
                assertThat(database.hasHistory()).isTrue();
                assertThat(node.findJob(UUID.randomUUID())).isEmpty();
                assertThat(node.node().id()).isEmpty();
              });
    }
  }

  @Test
  void pollingWithMigrationDisabledAcceptsPreparedSchema() throws SQLException {
    try (var database = new IsolatedDatabase()) {
      try (var deploy =
          Bikesched.builder().dataSource(database.dataSource).autoStartPolling(false).build()) {
        deploy.migrate();
      }
      runner(database)
          .withPropertyValues("bikesched.migrate-schema=false", "bikesched.auto-start.enabled=true")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Bikesched.class).node().isPolling()).isTrue();
              });
    }
  }

  private ApplicationContextRunner runner(IsolatedDatabase database) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class))
        .withBean("dataSource", DataSource.class, () -> database.dataSource)
        .withPropertyValues("bikesched.auto-start.enabled=false");
  }

  private static final class IsolatedDatabase implements AutoCloseable {
    private final String schema =
        "spring_migration_" + UUID.randomUUID().toString().replace("-", "");
    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();

    IsolatedDatabase() throws SQLException {
      try (var connection = AbstractSpringTest.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.execute("create schema " + schema);
      }
      dataSource.setUrl(POSTGRES.getJdbcUrl());
      dataSource.setUser(POSTGRES.getUsername());
      dataSource.setPassword(POSTGRES.getPassword());
      dataSource.setCurrentSchema(schema);
    }

    boolean hasHistory() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var result =
              statement.executeQuery("select to_regclass('bikesched_schema_migrations')")) {
        result.next();
        return result.getString(1) != null;
      }
    }

    @Override
    public void close() throws SQLException {
      try (var connection = AbstractSpringTest.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.execute("drop schema " + schema + " cascade");
      }
    }
  }
}
