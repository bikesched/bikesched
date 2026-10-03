package bikesched.core.internal;

import bikesched.core.BikeschedException;
import bikesched.core.JsonMapper;
import bikesched.core.internal.schema.SchemaMigrator;
import bikesched.core.job.Job;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Entry point to bikesched's database access: schema migration, the shared connection and
 * transaction helpers, and one store per table ({@link #nodes()}, {@link #jobs()}, {@link
 * #steps()}, {@link #events()}). Thread-safe; every call borrows its own connection from the data
 * source.
 *
 * <p>Stores run their statements through {@link Sql}, obtained from {@link #withConnection}, {@link
 * #inTransaction} or {@link #onCallerConnection}. {@link SQLException}s are wrapped in {@link
 * BikeschedException}. Writes are committed explicitly if the data source hands out connections
 * with auto-commit disabled.
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

  /**
   * Runs the work on one of bikesched's own connections, committing afterwards if the connection is
   * not in auto-commit mode (rolling back if the work fails). For single statements; work whose
   * statements must share a transaction uses {@link #inTransaction}.
   *
   * @param action describes the work for the exception message, e.g. {@code "Claiming jobs"}
   * @throws BikeschedException wrapping an {@link SQLException}, with message {@code "<action>
   *     failed"}
   */
  <T> T withConnection(String action, Sql.Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.getAutoCommit()) {
        return work.run(new Sql(connection));
      }
      try {
        T result = work.run(new Sql(connection));
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  /**
   * Runs a single insert, update or delete on one of bikesched's own connections, see {@link
   * #withConnection}.
   *
   * @return the number of affected rows
   */
  int update(String sql, String action, Object... parameters) {
    return withConnection(action, db -> db.update(sql, parameters));
  }

  /**
   * Runs the work on a caller-supplied connection, as part of the caller's transaction: the
   * connection is neither committed nor closed.
   *
   * @throws BikeschedException wrapping an {@link SQLException}, with message {@code "<action>
   *     failed"}
   */
  static <T> T onCallerConnection(Connection connection, String action, Sql.Work<T> work) {
    try {
      return work.run(new Sql(connection));
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  /**
   * Runs the work in a transaction on one of bikesched's own connections: committed if it
   * completes, rolled back if it throws. Needed whenever statements must share a transaction (e.g.
   * an advisory lock held across them), also with auto-commit connections.
   */
  <T> T inTransaction(String action, Sql.Work<T> work) {
    return inTransaction(action, false, work);
  }

  /**
   * Runs the work in a read-only transaction on one of bikesched's own connections: PostgreSQL
   * rejects any write in it. The connection's read-only setting is restored afterwards (pooled
   * connections are reused).
   */
  <T> T inReadOnlyTransaction(String action, Sql.Work<T> work) {
    return inTransaction(action, true, work);
  }

  private <T> T inTransaction(String action, boolean readOnly, Sql.Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      boolean wasReadOnly = connection.isReadOnly();
      if (autoCommit) {
        connection.setAutoCommit(false);
      }
      if (readOnly != wasReadOnly) {
        connection.setReadOnly(readOnly); // before the transaction starts (BEGIN READ ONLY)
      }
      try {
        T result = work.run(new Sql(connection));
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      } finally {
        if (readOnly != wasReadOnly) {
          connection.setReadOnly(wasReadOnly);
        }
        if (autoCommit) {
          connection.setAutoCommit(true);
        }
      }
    } catch (SQLException e) {
      throw new BikeschedException(action + " failed", e);
    }
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }
}
