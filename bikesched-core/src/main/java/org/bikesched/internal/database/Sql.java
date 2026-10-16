// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * Statement helpers on a connection: the only place in the stores that prepares statements and
 * reads result sets. Obtained from {@link DatabaseAccess#withConnection}, {@link
 * DatabaseAccess#inTransaction} or {@link DatabaseAccess#onCallerConnection}, which own the
 * connection, commit and exception handling; an {@code Sql} never commits or closes its connection.
 *
 * <p>Parameters are bound positionally with {@link PreparedStatement#setObject(int, Object)}; an
 * {@link Instant} is bound as {@code timestamptz}. Nullable parameters whose SQL type PostgreSQL
 * cannot infer (e.g. in {@code coalesce(?, ...)}) are passed {@link #typed typed}, durations
 * explicitly as {@link #millis} or {@link #seconds}.
 *
 * <p>The stores also report the jobs their statements make due ({@link #dueNow}, {@link #dueAt});
 * the {@link DatabaseAccess} passes them on to {@link DueJobs} once the work committed.
 */
public final class Sql {

  private final Connection connection;

  /** The JDBC query timeout of every statement in seconds; 0 for none. */
  private final int queryTimeoutSeconds;

  /** Types of jobs the work made due now, see {@link #dueNow}. */
  private final Set<String> dueNow = new LinkedHashSet<>();

  /** The earliest due time per type of the jobs the work made due later, see {@link #dueAt}. */
  private final Map<String, Instant> dueAt = new LinkedHashMap<>();

  public Sql(Connection connection) {
    this(connection, 0);
  }

  /**
   * @param connection the connection
   * @param queryTimeoutSeconds the JDBC query timeout of every statement in seconds, 0 for none
   */
  public Sql(Connection connection, int queryTimeoutSeconds) {
    this.connection = connection;
    if (queryTimeoutSeconds < 0) {
      throw new IllegalArgumentException("queryTimeoutSeconds must not be negative");
    }
    this.queryTimeoutSeconds = queryTimeoutSeconds;
  }

  /**
   * Reports that a statement of the work made jobs of this type due now (new, resumed, released
   * jobs): all nodes are told after the commit, see {@link DueJobs#dueNow}.
   *
   * @param type the job type
   */
  public void dueNow(String type) {
    dueNow.add(type);
  }

  /**
   * Reports that a statement of the work made a job of this type due at the given time (possibly
   * already), for this node's own wake-up after the commit, see {@link DueJobs#dueAt}.
   *
   * @param type the job type
   * @param at when it is due
   */
  public void dueAt(String type, Instant at) {
    dueAt.merge(type, at, (a, b) -> a.isBefore(b) ? a : b);
  }

  /**
   * Reports a new job: due now (all nodes are told) if {@code at} has passed, otherwise for this
   * node's own wake-up at {@code at}.
   *
   * @param type the job type
   * @param at when it is due
   */
  public void due(String type, Instant at) {
    if (at.isAfter(Instant.now())) {
      dueAt(type, at);
    } else {
      dueNow(type);
    }
  }

  /** The types reported by {@link #dueNow}. */
  Set<String> dueNowTypes() {
    return dueNow;
  }

  /** The due times reported by {@link #dueAt}, the earliest per type. */
  Map<String, Instant> dueAtTimes() {
    return dueAt;
  }

  /** Work on an {@link Sql}; any {@link SQLException} is wrapped by the caller of the work. */
  @FunctionalInterface
  public interface Work<T extends @Nullable Object> {
    T run(Sql sql) throws SQLException;
  }

  /** Maps the current row of a result set. */
  @FunctionalInterface
  public interface RowMapper<T> {
    T map(Row row) throws SQLException;
  }

  /**
   * Runs a query and maps its first row. The mapper must not return {@code null}, so that an empty
   * result always means "no row": map a nullable column into a record instead.
   *
   * @return the mapped first row, empty if the query returns no row
   * @throws NullPointerException if the mapper returns {@code null}
   */
  public <T> Optional<T> one(String sql, RowMapper<T> mapper, @Nullable Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = prepare(sql, parameters);
        ResultSet rs = statement.executeQuery()) {
      if (!rs.next()) {
        return Optional.empty();
      }
      return Optional.of(
          Objects.requireNonNull(
              mapper.map(new Row(rs)),
              "Row mapper returned null; map nullable columns to a record"));
    }
  }

  /** Runs a query and maps all rows, in result order. */
  public <T> List<T> list(String sql, RowMapper<T> mapper, @Nullable Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = prepare(sql, parameters);
        ResultSet rs = statement.executeQuery()) {
      Row row = new Row(rs);
      List<T> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(mapper.map(row));
      }
      return rows;
    }
  }

  /**
   * Runs an insert, update or delete without {@code returning}.
   *
   * @return the number of affected rows
   */
  public int update(String sql, @Nullable Object... parameters) throws SQLException {
    try (PreparedStatement statement = prepare(sql, parameters)) {
      return statement.executeUpdate();
    }
  }

  /** Runs a statement for its side effect only, e.g. {@code select pg_advisory_xact_lock(...)}. */
  public void execute(String sql, @Nullable Object... parameters) throws SQLException {
    try (PreparedStatement statement = prepare(sql, parameters)) {
      statement.execute();
    }
  }

  private PreparedStatement prepare(String sql, @Nullable Object[] parameters) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      if (queryTimeoutSeconds > 0) {
        statement.setQueryTimeout(queryTimeoutSeconds);
      }
      for (int i = 0; i < parameters.length; i++) {
        switch (parameters[i]) {
          case Typed typed -> statement.setObject(i + 1, typed.value(), typed.sqlType());
          case Array array ->
              statement.setArray(i + 1, connection.createArrayOf(array.type(), array.values()));
          case Instant instant -> statement.setObject(i + 1, instant.atOffset(ZoneOffset.UTC));
          case null, default -> statement.setObject(i + 1, parameters[i]);
        }
      }
      return statement;
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }

  /**
   * A parameter with an explicit SQL type, for {@code null}s whose type PostgreSQL cannot infer.
   *
   * @param value the value, may be {@code null}
   * @param sqlType the {@link Types} constant
   */
  public record Typed(@Nullable Object value, int sqlType) {}

  /** A nullable parameter with an explicit SQL type, see {@link Types}. */
  public static Typed typed(@Nullable Object value, int sqlType) {
    return new Typed(value, sqlType);
  }

  /**
   * An array parameter, e.g. for {@code unnest(?::uuid[])}.
   *
   * @param type the PostgreSQL element type, e.g. {@code uuid} or {@code text}
   * @param values the elements, may contain {@code null}s
   */
  // Only handed to JDBC, never compared: the array's identity semantics do not matter.
  @SuppressWarnings("ArrayRecordComponent")
  public record Array(String type, @Nullable Object[] values) {}

  /** An array parameter of the given PostgreSQL element type, see {@link Array}. */
  public static Array array(String type, @Nullable Object[] values) {
    return new Array(type, values);
  }

  /** A nullable {@code timestamptz} parameter. */
  public static Typed timestamp(@Nullable Instant instant) {
    return typed(
        instant == null ? null : instant.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  /** A nullable duration as a {@code bigint} parameter in milliseconds. */
  public static Typed millis(@Nullable Duration duration) {
    return typed(duration == null ? null : duration.toMillis(), Types.BIGINT);
  }

  /** A duration as fractional seconds, e.g. for {@code make_interval(secs => ?)}. */
  public static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }

  /**
   * Typed, null-safe access to the current row of a result set; columns are 1-based.
   *
   * <p>Whether a getter returns {@code null} depends on the column, which only the schema knows, so
   * the getters are not null-marked: NOT NULL columns map into non-null fields, nullable columns
   * into {@code @Nullable} ones.
   *
   * @param rs the result set, positioned on a row
   */
  @NullUnmarked
  public record Row(ResultSet rs) {

    String string(int column) throws SQLException {
      return rs.getString(column);
    }

    /** An {@code integer} column; {@code 0} for SQL {@code null}. */
    int integer(int column) throws SQLException {
      return rs.getInt(column);
    }

    /** A {@code boolean} column; {@code false} for SQL {@code null}. */
    boolean bool(int column) throws SQLException {
      return rs.getBoolean(column);
    }

    UUID uuid(int column) throws SQLException {
      return rs.getObject(column, UUID.class);
    }

    /** A {@code timestamptz} column, {@code null} for SQL {@code null}. */
    Instant instant(int column) throws SQLException {
      OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
      return time == null ? null : time.toInstant();
    }

    /**
     * A {@code text} column holding the name of an enum constant, {@code null} for SQL {@code
     * null}.
     */
    <E extends Enum<E>> E enumValue(int column, Class<E> type) throws SQLException {
      String name = rs.getString(column);
      return name == null ? null : Enum.valueOf(type, name);
    }

    /** A {@code bigint} column holding milliseconds, {@code null} for SQL {@code null}. */
    Duration millis(int column) throws SQLException {
      Long millis = rs.getObject(column, Long.class);
      return millis == null ? null : Duration.ofMillis(millis);
    }
  }
}
