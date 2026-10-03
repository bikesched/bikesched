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
 * Entry point to bikesched's database: schema migration and one store per table ({@link #nodes()},
 * {@link #jobs()}, {@link #steps()}, {@link #events()}). The stores run their statements through a
 * shared {@link DatabaseAccess}; they do not know this class. Thread-safe; every call borrows its
 * own connection from the data source.
 */
public final class Database {

  private final DataSource dataSource;

  private final JsonMapper jsonMapper;

  private final NodeStore nodes;

  private final JobStore jobs;

  private final StepStore steps;

  private final EventStore events;

  /**
   * Creates the database access.
   *
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   */
  public Database(DataSource dataSource, JsonMapper jsonMapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    var access = new DatabaseAccess(dataSource);
    this.nodes = new NodeStore(access);
    this.jobs = new JobStore(access);
    this.steps = new StepStore(access);
    this.events = new EventStore(access);
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
}
