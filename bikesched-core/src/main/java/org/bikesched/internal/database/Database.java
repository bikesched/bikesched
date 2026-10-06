// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.schema.SchemaMigrator;
import org.bikesched.job.Job;

/**
 * Entry point to bikesched's database: schema migration and one store per table ({@link #nodes()},
 * {@link #jobs()}, {@link #steps()}, {@link #events()}, {@link #history()}). The stores run their
 * statements through a shared {@link DatabaseAccess}; they do not know this class. Thread-safe;
 * every call borrows its own connection from the data source.
 */
public final class Database {

  private final DataSource dataSource;

  private final JsonMapper jsonMapper;

  private final NodeStore nodes;

  private final JobStore jobs;

  private final StepStore steps;

  private final EventStore events;

  private final HistoryStore history;

  /**
   * Creates the database access without job history.
   *
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   */
  public Database(DataSource dataSource, JsonMapper jsonMapper) {
    this(dataSource, jsonMapper, false);
  }

  /**
   * Creates the database access.
   *
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   * @param jobHistory whether the stores record their changes in the job history
   */
  public Database(DataSource dataSource, JsonMapper jsonMapper, boolean jobHistory) {
    this(dataSource, jsonMapper, jobHistory, null);
  }

  /**
   * Creates the database access.
   *
   * @param dataSource the PostgreSQL data source
   * @param jsonMapper maps payloads and step results of the {@link Job}s this instance claims
   * @param jobHistory whether the stores record their changes in the job history
   * @param statementTimeout the maximum duration of a statement of the stores, {@code null} to
   *     leave it to the data source
   */
  public Database(
      DataSource dataSource, JsonMapper jsonMapper, boolean jobHistory, Duration statementTimeout) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    var access = new DatabaseAccess(dataSource, statementTimeout);
    this.history = new HistoryStore(access, jobHistory);
    this.nodes = new NodeStore(access);
    this.jobs = new JobStore(access, history);
    this.steps = new StepStore(access, history);
    this.events = new EventStore(access, history);
  }

  /** The {@code bikesched_nodes} table. */
  public NodeStore nodes() {
    return nodes;
  }

  /** The {@code bikesched_jobs} table. */
  public JobStore jobs() {
    return jobs;
  }

  /** The {@code bikesched_steps} table. */
  public StepStore steps() {
    return steps;
  }

  /** The {@code bikesched_events} table and steps waiting for events. */
  public EventStore events() {
    return events;
  }

  /** The {@code bikesched_job_history} table, written by the other stores if enabled. */
  public HistoryStore history() {
    return history;
  }

  /** The mapper for payloads and step results; handed to the claimed {@link Job}s. */
  public JsonMapper jsonMapper() {
    return jsonMapper;
  }

  /**
   * Brings the schema up to date (see {@link SchemaMigrator}).
   *
   * @return the migration versions applied by this call
   */
  public List<Integer> migrate() {
    return new SchemaMigrator(dataSource).migrate();
  }

  /**
   * Describes the database for logging, e.g. {@code jdbc:postgresql://localhost:5432/bikesched
   * (PostgreSQL 18.0)}. The URL is {@link #redactUrl(String) redacted}, so it contains no
   * credentials.
   *
   * @return the redacted JDBC URL and the database product and version
   */
  public String describe() {
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
  public static String redactUrl(String url) {
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
