// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bikesched.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SchemaMigratorTest {

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private PGSimpleDataSource dataSource;
  private LogCapture logs;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    execute(
        "drop table if exists bikesched_job_history, bikesched_events, bikesched_steps, bikesched_jobs,"
            + " bikesched_nodes, "
            + SchemaMigrator.HISTORY_TABLE
            + " cascade");

    logs = LogCapture.of(SchemaMigrator.class);
  }

  @AfterEach
  void stopCapturingLogs() {
    logs.close();
  }

  @Test
  void createsAllTables() throws SQLException {
    assertThat(new SchemaMigrator(dataSource).migrate()).containsExactly(1);

    assertThat(
            queryStrings(
                """
                select table_name from information_schema.tables
                where table_schema = current_schema() and table_name like 'bikesched\\_%' order by table_name"""))
        .containsExactly(
            "bikesched_events",
            "bikesched_job_history",
            "bikesched_jobs",
            "bikesched_nodes",
            "bikesched_schema_migrations",
            "bikesched_steps");
    assertThat(
            queryStrings(
                "select version || ':' || description from " + SchemaMigrator.HISTORY_TABLE))
        .containsExactly("1:create nodes, jobs, steps, events and job history tables");

    assertThat(logEvents(Level.DEBUG))
        .containsExactly(
            "DEBUG Acquiring bikesched schema migration lock",
            "DEBUG Acquired bikesched schema migration lock",
            "INFO Migrating bikesched schema from version V0: 1 pending migration(s)",
            "INFO Applying bikesched migration V1 (create nodes, jobs, steps, events and job history tables)",
            "INFO Applied bikesched migration V1 (create nodes, jobs, steps, events and job history tables) in <n> ms",
            "INFO bikesched schema migrated to version V1",
            "DEBUG Released bikesched schema migration lock");
  }

  @Test
  void secondRunIsNoOp() {
    var migrator = new SchemaMigrator(dataSource);
    migrator.migrate();
    logs.clear();

    assertThat(migrator.migrate()).isEmpty();

    assertThat(logEvents(Level.INFO))
        .containsExactly("INFO bikesched schema is up to date at version V1");
  }

  @Test
  void concurrentNodesApplyMigrationsExactlyOnce() throws Exception {
    int nodes = 8;
    List<Future<List<Integer>>> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(nodes)) {
      for (int i = 0; i < nodes; i++) {
        Callable<List<Integer>> task = () -> new SchemaMigrator(dataSource).migrate();
        results.add(executor.submit(task));
      }
    }

    List<Integer> allApplied = new ArrayList<>();
    for (Future<List<Integer>> result : results) {
      allApplied.addAll(result.get());
    }
    assertThat(allApplied).containsExactly(1);

    List<String> events = logEvents(Level.INFO);
    assertThat(events)
        .filteredOn(e -> e.startsWith("INFO Applying bikesched migration"))
        .containsExactly(
            "INFO Applying bikesched migration V1 (create nodes, jobs, steps, events and job history tables)");
    assertThat(events)
        .filteredOn(e -> e.equals("INFO bikesched schema is up to date at version V1"))
        .hasSize(nodes - 1);
  }

  @Test
  void failsWhenAppliedMigrationWasModified() throws SQLException {
    new SchemaMigrator(dataSource).migrate();
    execute(
        "update " + SchemaMigrator.HISTORY_TABLE + " set checksum = 'tampered' where version = 1");
    logs.clear();

    assertThatThrownBy(() -> new SchemaMigrator(dataSource).migrate())
        .isInstanceOf(SchemaMigrationException.class)
        .hasMessageContaining("Checksum mismatch for applied migration V1");

    assertThat(logEvents(Level.INFO))
        .containsExactly(
            "ERROR Checksum mismatch for applied bikesched migration V1 "
                + "(org/bikesched/migrations/V001__create_tables.sql): applied migrations must not change");
  }

  @Test
  void failedMigrationIsRolledBack() throws SQLException {
    var broken =
        List.of(
            SchemaMigrator.MIGRATIONS.getFirst(),
            new Migration(2, "broken", "org/bikesched/test-migrations/V002__broken.sql"));

    assertThatThrownBy(() -> new SchemaMigrator(dataSource, broken).migrate())
        .isInstanceOf(SchemaMigrationException.class)
        .hasMessageContaining("Migration V2");

    // V1 committed, V2 (including its first, valid statement) rolled back.
    assertThat(queryStrings("select version::text from " + SchemaMigrator.HISTORY_TABLE))
        .containsExactly("1");
    assertThat(queryStrings("select to_regclass('bikesched_broken')::text"))
        .containsExactly((String) null);

    assertThat(logEvents(Level.INFO))
        .containsExactly(
            "INFO Migrating bikesched schema from version V0: 2 pending migration(s)",
            "INFO Applying bikesched migration V1 (create nodes, jobs, steps, events and job history tables)",
            "INFO Applied bikesched migration V1 (create nodes, jobs, steps, events and job history tables) in <n> ms",
            "INFO Applying bikesched migration V2 (broken)",
            "ERROR bikesched migration V2 (org/bikesched/test-migrations/V002__broken.sql) failed and was rolled back");
    ILoggingEvent error =
        logs.events().stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
    assertThat(error.getThrowableProxy()).as("error is logged with its cause").isNotNull();
    assertThat(error.getThrowableProxy().getClassName())
        .isEqualTo("org.postgresql.util.PSQLException");
  }

  @Test
  void warnsAboutUnknownAppliedVersions() throws SQLException {
    new SchemaMigrator(dataSource).migrate();
    execute(
        "insert into "
            + SchemaMigrator.HISTORY_TABLE
            + " (version, description, checksum, execution_time_millis) values (99, 'from the future', 'x', 0)");
    logs.clear();

    assertThat(new SchemaMigrator(dataSource).migrate()).isEmpty();

    assertThat(logEvents(Level.INFO))
        .containsExactly(
            "WARN bikesched schema contains migration V99 unknown to this library version; "
                + "ignoring it (applied by a newer bikesched version?)",
            "INFO bikesched schema is up to date at version V99");
  }

  @Test
  void nodesMayShareAName() throws SQLException {
    new SchemaMigrator(dataSource).migrate();

    // A restarted instance registers next to the stale row of its previous run (ADR 0051).
    execute(
        "insert into bikesched_nodes (id, name) values (gen_random_uuid(), 'same'),"
            + " (gen_random_uuid(), 'same')");
  }

  @Test
  void nodesClaimDisjointJobsWithSkipLocked() throws SQLException {
    new SchemaMigrator(dataSource).migrate();
    UUID nodeA = UUID.randomUUID();
    UUID nodeB = UUID.randomUUID();
    execute(
        "insert into bikesched_nodes (id, name) values ('"
            + nodeA
            + "', 'a'), ('"
            + nodeB
            + "', 'b')");
    execute(
        """
                insert into bikesched_jobs (id, name, priority)
                select gen_random_uuid(), 'test', n % 3 from generate_series(1, 10) n""");

    String claim =
        """
                update bikesched_jobs
                   set state = 'RUNNING', locked_by_node_id = ?, lock_token = gen_random_uuid(), locked_at = now(), attempts = attempts + 1, updated_at = now()
                 where id in (select id from bikesched_jobs
                               where state = 'SCHEDULED' and scheduled_at <= now()
                               order by priority desc, scheduled_at
                               limit 4
                               for update skip locked)
                returning id""";

    try (Connection a = dataSource.getConnection();
        Connection b = dataSource.getConnection()) {
      a.setAutoCommit(false);
      b.setAutoCommit(false);
      List<UUID> claimedByA = claim(a, claim, nodeA);
      // A has not committed yet: B must skip A's locked rows instead of blocking.
      List<UUID> claimedByB = claim(b, claim, nodeB);
      a.commit();
      b.commit();

      assertThat(claimedByA).hasSize(4);
      assertThat(claimedByB).hasSize(4).doesNotContainAnyElementsOf(claimedByA);
    }
    assertThat(queryStrings("select count(*)::text from bikesched_jobs where state = 'SCHEDULED'"))
        .containsExactly("2");
  }

  /**
   * Captured events at or above {@code minLevel} as "LEVEL message"; durations normalized to "<n>
   * ms".
   */
  private List<String> logEvents(Level minLevel) {
    return logs.events().stream()
        .filter(e -> e.getLevel().isGreaterOrEqual(minLevel))
        .map(
            e -> e.getLevel() + " " + e.getFormattedMessage().replaceAll("in \\d+ ms", "in <n> ms"))
        .toList();
  }

  private static List<UUID> claim(Connection connection, String sql, UUID node)
      throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, node);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getObject(1, UUID.class));
        }
      }
    }
    return ids;
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private List<String> queryStrings(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        var rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
