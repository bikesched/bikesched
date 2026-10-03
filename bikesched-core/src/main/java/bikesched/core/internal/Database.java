package bikesched.core.internal;

import bikesched.core.BikeschedException;
import bikesched.core.JsonMapper;
import bikesched.core.internal.schema.SchemaMigrator;
import bikesched.core.job.Job;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Entry point to bikesched's database access: schema migration, the shared connection and
 * transaction helpers, and one store per table ({@link #nodes()}, {@link #jobs()}, {@link
 * #steps()}, {@link #events()}). Thread-safe; every call borrows its own connection from the data
 * source.
 *
 * <p>{@link SQLException}s are wrapped in {@link BikeschedException}. Writes are committed
 * explicitly if the data source hands out connections with auto-commit disabled.
 */
public final class Database {

  private final DataSource dataSource;

  private final JsonMapper jsonMapper;

  private final NodeStore nodes = new NodeStore(this);

  private final JobStore jobs = new JobStore(this);

  private final StepStore steps = new StepStore(this);

  private final EventStore events = new EventStore(this);

  /**
   * Creates the database access.
   *
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   */
  public Database(DataSource dataSource, JsonMapper jsonMapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
  }

  /** The {@code bikesched_nodes} table. */
  NodeStore nodes() {
    return nodes;
  }

  /** The {@code bikesched_jobs} table. */
  JobStore jobs() {
    return jobs;
  }

  /** The {@code bikesched_steps} table. */
  StepStore steps() {
    return steps;
  }

  /** The {@code bikesched_events} table and steps waiting for events. */
  EventStore events() {
    return events;
  }

  /** The mapper for payloads and step results; handed to the claimed {@link Job}s. */
  JsonMapper jsonMapper() {
    return jsonMapper;
  }

  /**
   * Brings the schema up to date (see {@link SchemaMigrator}).
   *
   * @return the migration versions applied by this call
   */
  List<Integer> migrate() {
    return new SchemaMigrator(dataSource).migrate();
  }

  /**
   * Describes the database for logging, e.g. {@code jdbc:postgresql://localhost:5432/bikesched
   * (PostgreSQL 18.0)}. The URL is {@link #redactUrl(String) redacted}, so it contains no
   * credentials.
   *
   * @return the redacted JDBC URL and the database product and version
   */
  String describe() {
    try (Connection connection = dataSource.getConnection()) {
      var metaData = connection.getMetaData();
      return redactUrl(metaData.getURL())
          + " ("
          + metaData.getDatabaseProductName()
          + " "
          + metaData.getDatabaseProductVersion()
          + ")";
    } catch (SQLException e) {
      throw new BikeschedException("Reading database metadata failed", e);
    }
  }

  /**
   * Removes everything from a JDBC URL that may carry credentials: user info before the host
   * ({@code //user:password@host}) and all properties ({@code ?user=...&password=...}, and {@code
   * ;key=value} style properties of other drivers).
   *
   * @param url the JDBC URL, may be {@code null}
   * @return the URL without credentials, e.g. {@code jdbc:postgresql://localhost:5432/bikesched}
   */
  static String redactUrl(String url) {
    if (url == null) {
      return "<unknown>";
    }
    int properties = url.length();
    for (char separator : new char[] {'?', ';'}) {
      int index = url.indexOf(separator);
      if (index >= 0) {
        properties = Math.min(properties, index);
      }
    }
    return url.substring(0, properties).replaceFirst("//[^/]*@", "//");
  }

  static Duration toDuration(Long millis) {
    return millis == null ? null : Duration.ofMillis(millis);
  }

  static Instant toInstant(OffsetDateTime time) {
    return time == null ? null : time.toInstant();
  }

  /** Borrows a connection from the data source; the caller closes it. */
  Connection connection() throws SQLException {
    return dataSource.getConnection();
  }

  /** Work on a connection within a transaction. */
  @FunctionalInterface
  interface TransactionWork<T> {
    T run(Connection connection) throws SQLException;
  }

  /**
   * Runs the work in a transaction on one of bikesched's own connections: committed if it
   * completes, rolled back if it throws. Needed whenever statements must share a transaction (e.g.
   * an advisory lock held across them), also with auto-commit connections.
   */
  <T> T inTransaction(String action, TransactionWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      if (autoCommit) {
        connection.setAutoCommit(false);
      }
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      } finally {
        if (autoCommit) {
          connection.setAutoCommit(true);
        }
      }
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  int update(String sql, String action, Object... parameters) {
    try (Connection connection = dataSource.getConnection()) {
      int updated = execute(connection, sql, parameters);
      commitIfNeeded(connection);
      return updated;
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  /** Executes an update on the given connection without committing or closing it. */
  static int execute(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      return statement.executeUpdate();
    }
  }

  static void commitIfNeeded(Connection connection) throws SQLException {
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }
}
