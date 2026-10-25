// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class OptionalSchemaMigrationTest extends AbstractBikeschedTest {

  private String schema;
  private PGSimpleDataSource isolated;

  @BeforeEach
  void createSchema() throws SQLException {
    schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    execute("create schema " + schema);
    isolated = new PGSimpleDataSource();
    isolated.setUrl(POSTGRES.getJdbcUrl());
    isolated.setUser(POSTGRES.getUsername());
    isolated.setPassword(POSTGRES.getPassword());
    isolated.setCurrentSchema(schema);
  }

  @AfterEach
  void dropSchema() throws SQLException {
    execute("drop schema " + schema + " cascade");
  }

  private BikeschedBuilder builder() {
    return Bikesched.builder(isolated).nodeName(schema).autoStartPolling(false);
  }

  @Test
  void explicitMigrationWorksWithAutomaticMigrationDisabledAndAfterFailure() {
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThatThrownBy(() -> scheduler.findJob(UUID.randomUUID()))
          .isInstanceOf(BikeschedException.class);
      scheduler.migrate();
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      assertThat(scheduler.node().id()).isNull();
    }
  }

  @Test
  void explicitMigrationStillChecksHistoryAfterSuccessfulValidation() throws SQLException {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      sql("update bikesched_schema_migrations set checksum = 'tampered'");
      assertThatThrownBy(scheduler::migrate)
          .isInstanceOf(BikeschedException.class)
          .hasMessageContaining("Checksum mismatch");
    }
  }

  @Test
  void defaultStillMigratesOnFirstUse() throws SQLException {
    try (var scheduler = builder().build()) {
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      assertThat(history()).hasSize(1);
    }
  }

  @Test
  void validationFailureAtBuildDoesNotRegisterNode() {
    assertThatThrownBy(() -> builder().migrateSchema(false).autoStartPolling(true).build())
        .isInstanceOf(BikeschedException.class)
        .hasMessageContaining("bikesched_schema_migrations");
  }

  @Test
  void retriesValidationAfterDeploymentAndThenStartsAndSchedules() {
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThatThrownBy(scheduler::start).isInstanceOf(BikeschedException.class);
      try (var deploy = builder().build()) {
        deploy.migrate();
      }
      scheduler.registerJobHandler("migration-test", job -> {});
      assertThat(scheduler.start().id()).isNotNull();
      var id = scheduler.newJob("migration-test").schedule();
      assertThat(scheduler.findJob(id)).isPresent();
    }
  }

  @Test
  void unknownVersionsWarnAndDoNotPreventValidation() throws SQLException {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    sql(
        "insert into bikesched_schema_migrations (version, description, checksum, execution_time_millis)"
            + " values (99, 'future', 'unknown', 0)");
    var before = history();
    isolated.setOptions("-c default_transaction_read_only=on");
    try (var logs = LogCapture.of(org.bikesched.internal.database.schema.SchemaMigrator.class);
        var scheduler = builder().migrateSchema(false).build()) {
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      assertThat(logs.events())
          .anySatisfy(
              event -> assertThat(event.getFormattedMessage()).contains("migration V99 unknown"));
    }
    assertThat(history()).isEqualTo(before);
  }

  @Test
  void explicitMigrationLogsDatabaseOnFirstUse() {
    try (var logs = LogCapture.of(org.bikesched.internal.database.DatabaseInitializer.class);
        var scheduler = builder().migrateSchema(false).build()) {
      scheduler.migrate();
      assertThat(logs.events())
          .anySatisfy(
              event ->
                  assertThat(event.getFormattedMessage()).startsWith("Database: jdbc:postgresql:"));
    }
  }

  @Test
  void applicationRoleWithoutDdlRightsCanStartAndSchedule() throws SQLException {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    String role = schema + "_app";
    execute("create role " + role);
    try {
      execute("grant usage on schema " + schema + " to " + role);
      execute(
          "grant select, insert, update, delete on all tables in schema " + schema + " to " + role);
      execute("grant usage on all sequences in schema " + schema + " to " + role);
      isolated.setOptions("-c role=" + role);
      assertThatThrownBy(() -> sql("create table forbidden (id int)"))
          .isInstanceOf(SQLException.class);
      try (var scheduler = builder().migrateSchema(false).autoStartPolling(true).build()) {
        assertThat(scheduler.node().id()).isNotNull();
        scheduler.registerJobHandler("no-ddl", job -> {});
        var id = scheduler.newJob("no-ddl").schedule();
        assertThat(scheduler.findJob(id)).isPresent();
      }
    } finally {
      execute("drop owned by " + role);
      execute("drop role " + role);
    }
  }

  @Test
  void explicitMigrationRejectsClosedInstances() {
    var scheduler = builder().migrateSchema(false).build();
    scheduler.close();
    assertThatThrownBy(scheduler::migrate).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void initializedSchemaCanBeUsedWithReadOnlyConnections() {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    isolated.setOptions("-c default_transaction_read_only=on");
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      assertThat(scheduler.findJob(UUID.randomUUID())).isEmpty();
      assertThat(scheduler.node().id()).isNull();
    }
  }

  @Test
  void missingVersionFailsWithoutWrites() throws SQLException {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    sql("delete from bikesched_schema_migrations");
    isolated.setOptions("-c default_transaction_read_only=on");
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThatThrownBy(() -> scheduler.findJob(UUID.randomUUID()))
          .isInstanceOf(BikeschedException.class)
          .hasMessageContaining("Missing migration versions: [1]")
          .hasMessageContaining("Bikesched.migrate()")
          .hasMessageContaining("org/bikesched/migrations/");
    }
    assertThat(history()).isEmpty();
  }

  @Test
  void checksumMismatchFailsWithoutWrites() throws SQLException {
    try (var deploy = builder().build()) {
      deploy.migrate();
    }
    sql("update bikesched_schema_migrations set checksum = 'tampered'");
    isolated.setOptions("-c default_transaction_read_only=on");
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThatThrownBy(() -> scheduler.findJob(UUID.randomUUID()))
          .isInstanceOf(BikeschedException.class)
          .hasMessageContaining("Checksum mismatch for applied migration V1")
          .hasMessageContaining("Restore")
          .hasMessageContaining("V001__create_tables.sql");
    }
    assertThat(history()).containsExactly("1:tampered");
  }

  private void sql(String sql) throws SQLException {
    try (var connection = isolated.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private java.util.List<String> history() throws SQLException {
    var values = new java.util.ArrayList<String>();
    try (var connection = isolated.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select version || ':' || checksum from bikesched_schema_migrations order by version")) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }

  @Test
  void missingHistoryFailsLazilyWithoutCreatingTables() throws SQLException {
    try (var scheduler = builder().migrateSchema(false).build()) {
      assertThatThrownBy(() -> scheduler.findJob(UUID.randomUUID()))
          .isInstanceOf(BikeschedException.class)
          .hasMessageContaining("bikesched_schema_migrations")
          .hasMessageContaining("Bikesched.migrate()")
          .hasMessageContaining("org/bikesched/migrations/");
      try (var connection = isolated.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select count(*) from information_schema.tables where table_schema = '"
                      + schema
                      + "'")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
    }
  }
}
