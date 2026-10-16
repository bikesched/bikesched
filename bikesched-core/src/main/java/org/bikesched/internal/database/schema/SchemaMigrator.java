// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and upgrades the bikesched tables in PostgreSQL.
 *
 * <p>Migrations are versioned SQL scripts on the classpath ({@code org/bikesched/migrations/}).
 * Applied versions are recorded in {@value #HISTORY_TABLE}. Each migration runs in its own
 * transaction, and the whole run holds a PostgreSQL advisory lock, so many nodes can call {@link
 * #migrate()} at startup concurrently: exactly one applies pending migrations, the others wait and
 * then find nothing left to do.
 *
 * <p>Migrations already applied must never be edited; a changed checksum fails the run. Versions in
 * the database that this library does not know (applied by a newer library version, e.g. during a
 * rolling deployment) are ignored with a warning.
 */
public final class SchemaMigrator {

  /** Name of the table recording applied migrations. */
  public static final String HISTORY_TABLE = "bikesched_schema_migrations";

  /** All migrations shipped with this library, in version order. Append only. */
  static final List<Migration> MIGRATIONS =
      List.of(
          new Migration(
              1,
              "create nodes, jobs, steps, events and job history tables",
              "org/bikesched/migrations/V001__create_tables.sql"));

  /** Advisory lock key; arbitrary constant ("bikeschd" in ASCII) shared by all bikesched nodes. */
  static final long LOCK_KEY = 0x62696b6573636864L;

  private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

  private final DataSource dataSource;
  private final List<Migration> migrations;

  /**
   * Creates a migrator for the given database.
   *
   * @param dataSource the PostgreSQL data source; tables are created in its default schema
   */
  public SchemaMigrator(DataSource dataSource) {
    this(dataSource, MIGRATIONS);
  }

  SchemaMigrator(DataSource dataSource, List<Migration> migrations) {
    this.dataSource = dataSource;
    for (int i = 1; i < migrations.size(); i++) {
      if (migrations.get(i).version() <= migrations.get(i - 1).version()) {
        throw new IllegalArgumentException("Migration versions must be strictly increasing");
      }
    }
    this.migrations = List.copyOf(migrations);
  }

  /**
   * Applies all pending migrations.
   *
   * @return the versions applied by this call (empty if the schema was already up to date)
   * @throws SchemaMigrationException if a migration fails or an applied migration was modified
   */
  public List<Integer> migrate() {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(true);
      LOG.debug("Acquiring bikesched schema migration lock");
      execute(connection, "select pg_advisory_lock(" + LOCK_KEY + ")");
      LOG.debug("Acquired bikesched schema migration lock");
      try {
        return migrateLocked(connection);
      } finally {
        execute(connection, "select pg_advisory_unlock(" + LOCK_KEY + ")");
        connection.setAutoCommit(autoCommit);
        LOG.debug("Released bikesched schema migration lock");
      }
    } catch (SQLException e) {
      throw new SchemaMigrationException("Schema migration failed", e);
    }
  }

  /** Validates recorded migrations without creating or changing any database objects. */
  public void validate() {
    try (Connection connection = dataSource.getConnection()) {
      Map<Integer, String> applied = appliedChecksums(connection);
      List<Migration> pending = pendingMigrations(applied);
      if (!pending.isEmpty()) {
        throw new SchemaMigrationException(
            "Missing migration versions: "
                + pending.stream().map(Migration::version).toList()
                + "; automatic schema migration is disabled. Run Bikesched.migrate() with a "
                + "migration-capable data source before application use "
                + "(scripts: org/bikesched/migrations/).");
      }
      LOG.info("bikesched schema migration history validated");
    } catch (SQLException e) {
      throw new SchemaMigrationException(
          "Cannot read bikesched_schema_migrations; automatic schema migration is disabled. "
              + "Run Bikesched.migrate() with a migration-capable data source before application use "
              + "(scripts: org/bikesched/migrations/), and grant SELECT on the history table.",
          e);
    }
  }

  private List<Integer> migrateLocked(Connection connection) throws SQLException {
    execute(
        connection,
        """
                create table if not exists %s (
                    version           integer     primary key,
                    description       text        not null,
                    checksum          text        not null,
                    installed_at      timestamptz not null default now(),
                    execution_time_ms bigint      not null
                )"""
            .formatted(HISTORY_TABLE));

    Map<Integer, String> applied = appliedChecksums(connection);
    List<Migration> pending = pendingMigrations(applied);

    int currentVersion = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    if (pending.isEmpty()) {
      LOG.info("bikesched schema is up to date at version V{}", currentVersion);
      return List.of();
    }

    LOG.info(
        "Migrating bikesched schema from version V{}: {} pending migration(s)",
        currentVersion,
        pending.size());
    List<Integer> newlyApplied = new ArrayList<>();
    for (Migration migration : pending) {
      apply(connection, migration);
      newlyApplied.add(migration.version());
    }
    LOG.info("bikesched schema migrated to version V{}", newlyApplied.getLast());
    return newlyApplied;
  }

  private List<Migration> pendingMigrations(Map<Integer, String> applied) {
    warnAboutUnknownVersions(applied);

    List<Migration> pending = new ArrayList<>();
    for (Migration migration : migrations) {
      String appliedChecksum = applied.get(migration.version());
      if (appliedChecksum == null) {
        pending.add(migration);
      } else if (!appliedChecksum.equals(migration.checksum())) {
        LOG.error(
            "Checksum mismatch for applied bikesched migration V{} ({}): applied migrations must not change",
            migration.version(),
            migration.resource());
        throw new SchemaMigrationException(
            "Checksum mismatch for applied migration V"
                + migration.version()
                + " ("
                + migration.resource()
                + "): applied migrations must not change. Restore the original migration scripts "
                + "and use the matching library/schema version; do not rewrite recorded checksums.");
      }
    }

    return pending;
  }

  private void warnAboutUnknownVersions(Map<Integer, String> applied) {
    for (int version : applied.keySet()) {
      if (migrations.stream().noneMatch(m -> m.version() == version)) {
        LOG.warn(
            "bikesched schema contains migration V{} unknown to this library version; "
                + "ignoring it (applied by a newer bikesched version?)",
            version);
      }
    }
  }

  private static Map<Integer, String> appliedChecksums(Connection connection) throws SQLException {
    Map<Integer, String> applied = new TreeMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("select version, checksum from " + HISTORY_TABLE)) {
      while (rs.next()) {
        applied.put(rs.getInt(1), rs.getString(2));
      }
    }
    return applied;
  }

  private static void apply(Connection connection, Migration migration) throws SQLException {
    LOG.info("Applying bikesched migration V{} ({})", migration.version(), migration.description());
    long start = System.nanoTime();
    connection.setAutoCommit(false);
    try {
      execute(connection, migration.sql());
      long millis = (System.nanoTime() - start) / 1_000_000;
      try (PreparedStatement insert =
          connection.prepareStatement(
              "insert into "
                  + HISTORY_TABLE
                  + " (version, description, checksum, execution_time_ms) values (?, ?, ?, ?)")) {
        insert.setInt(1, migration.version());
        insert.setString(2, migration.description());
        insert.setString(3, migration.checksum());
        insert.setLong(4, millis);
        insert.executeUpdate();
      }
      connection.commit();
      LOG.info(
          "Applied bikesched migration V{} ({}) in {} ms",
          migration.version(),
          migration.description(),
          millis);
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      LOG.error(
          "bikesched migration V{} ({}) failed and was rolled back",
          migration.version(),
          migration.resource(),
          e);
      throw new SchemaMigrationException(
          "Migration V"
              + migration.version()
              + " ("
              + migration.resource()
              + ") failed and was rolled back",
          e);
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
