// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection and transaction handling for the stores: runs their {@link Sql} work on a connection
 * of the data source (or on a caller's connection), commits or rolls back, and turns a failing
 * statement into an exception via the caller's {@link SqlExceptionHandler}. Thread-safe; every call
 * borrows its own connection.
 *
 * <p>Writes are committed explicitly if the data source hands out connections with auto-commit
 * disabled. Runtime exceptions thrown by the work are rethrown as they are (after a rollback).
 *
 * <p>With a statement timeout, every statement gets it as JDBC query timeout (whole seconds,
 * rounded up): a statement still running then is cancelled and fails like any other.
 *
 * <p>The jobs the work reported due on its {@link Sql} are passed on to the {@link DueJobs} after
 * the commit; on a caller's connection, the notification is sent in the caller's transaction
 * instead.
 */
public final class DatabaseAccess {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseAccess.class);

  private final DataSource dataSource;
  private final int queryTimeoutSeconds;
  private volatile DueJobs dueJobs = DueJobs.NONE;

  DatabaseAccess(DataSource dataSource) {
    this(dataSource, null);
  }

  /**
   * @param dataSource the data source
   * @param statementTimeout the maximum duration of a statement, {@code null} to leave it to the
   *     data source
   */
  DatabaseAccess(DataSource dataSource, Duration statementTimeout) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.queryTimeoutSeconds = statementTimeout == null ? 0 : seconds(statementTimeout);
  }

  /** JDBC query timeouts are whole seconds: rounded up, at least 1. */
  private static int seconds(Duration timeout) {
    long seconds = timeout.toSeconds() + (timeout.getNano() > 0 ? 1 : 0);
    return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
  }

  private Sql sql(Connection connection) {
    return new Sql(connection, queryTimeoutSeconds);
  }

  /** Sets where committed work reports the jobs it made due. */
  void dueJobs(DueJobs dueJobs) {
    this.dueJobs = Objects.requireNonNull(dueJobs, "dueJobs");
  }

  /** Passes on the jobs the committed work made due; never throws, the work is committed. */
  private void report(Sql sql) {
    if (sql.dueNowTypes().isEmpty() && sql.dueAtTimes().isEmpty()) {
      return;
    }
    DueJobs target = dueJobs;
    try {
      if (!sql.dueNowTypes().isEmpty()) {
        target.dueNow(Set.copyOf(sql.dueNowTypes()));
      }
      sql.dueAtTimes().forEach(target::dueAt);
    } catch (RuntimeException e) {
      LOG.warn("Reporting due jobs failed, they start on the next poll", e);
    }
  }

  /**
   * Turns a failed statement into the exception to throw. Only called if the work fails, so
   * building the message costs nothing on success.
   */
  @FunctionalInterface
  public interface SqlExceptionHandler {

    /**
     * Creates the exception to throw.
     *
     * @param e the failure
     * @return the exception to throw, e.g. a {@code BikeschedException} with {@code e} as its cause
     */
    RuntimeException handle(SQLException e);
  }

  /**
   * Runs the work on one of bikesched's own connections, committing afterwards if the connection is
   * not in auto-commit mode (rolling back if the work fails). For single statements; work whose
   * statements must share a transaction uses {@link #inTransaction}.
   *
   * @param work the statements
   * @param onFailure creates the exception thrown if a statement fails
   * @return the work's result
   */
  public <T> T withConnection(Sql.Work<T> work, SqlExceptionHandler onFailure) {
    try (Connection connection = dataSource.getConnection()) {
      Sql sql = sql(connection);
      if (connection.getAutoCommit()) {
        T result = work.run(sql);
        report(sql);
        return result;
      }
      try {
        T result = work.run(sql);
        connection.commit();
        report(sql);
        return result;
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw failure(onFailure, e);
    }
  }

  /**
   * Runs the work in a transaction on one of bikesched's own connections: committed if it
   * completes, rolled back if it throws. Needed whenever statements must share a transaction (e.g.
   * an advisory lock held across them), also with auto-commit connections.
   *
   * @param work the statements
   * @param onFailure creates the exception thrown if a statement fails
   * @return the work's result
   */
  public <T> T inTransaction(Sql.Work<T> work, SqlExceptionHandler onFailure) {
    return inTransaction(false, work, onFailure);
  }

  /**
   * Runs the work in a read-only transaction on one of bikesched's own connections: PostgreSQL
   * rejects any write in it. The connection's read-only setting is restored afterwards (pooled
   * connections are reused).
   *
   * @param work the statements
   * @param onFailure creates the exception thrown if a statement fails
   * @return the work's result
   */
  public <T> T inReadOnlyTransaction(Sql.Work<T> work, SqlExceptionHandler onFailure) {
    return inTransaction(true, work, onFailure);
  }

  /**
   * Runs the work on a caller-supplied connection, as part of the caller's transaction: the
   * connection is neither committed nor closed. Jobs the work made due now are notified in the
   * caller's transaction ({@link JobStore#NOTIFY_DUE_SQL}, sent on commit, not on rollback) if the
   * {@link DueJobs} notify.
   *
   * @param connection the caller's connection
   * @param work the statements
   * @param onFailure creates the exception thrown if a statement fails
   * @return the work's result
   */
  public <T> T onCallerConnection(
      Connection connection, Sql.Work<T> work, SqlExceptionHandler onFailure) {
    try {
      Sql sql = sql(connection);
      T result = work.run(sql);
      if (!sql.dueNowTypes().isEmpty() && dueJobs.isNotifying()) {
        sql.execute(JobStore.NOTIFY_DUE_SQL, Sql.array("text", sql.dueNowTypes().toArray()));
      }
      sql.dueNowTypes().clear(); // not committed yet: due-later times only arm this node's timer
      report(sql);
      return result;
    } catch (SQLException e) {
      throw failure(onFailure, e);
    }
  }

  /**
   * Borrows a connection for listening on a channel until the returned listening is closed.
   *
   * @param channel the channel
   * @param onFailure creates the exception thrown if connecting or listening fails
   * @return the listening connection
   * @throws UnsupportedOperationException if the connection is no PostgreSQL JDBC connection
   */
  DueJobsListening listen(String channel, SqlExceptionHandler onFailure) {
    try {
      return DueJobsListening.open(dataSource.getConnection(), channel);
    } catch (SQLException e) {
      throw failure(onFailure, e);
    }
  }

  private <T> T inTransaction(boolean readOnly, Sql.Work<T> work, SqlExceptionHandler onFailure) {
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
        Sql sql = sql(connection);
        T result = work.run(sql);
        connection.commit();
        report(sql);
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
      throw failure(onFailure, e);
    }
  }

  private static RuntimeException failure(SqlExceptionHandler onFailure, SQLException e) {
    return Objects.requireNonNull(onFailure.handle(e), "SqlExceptionHandler returned null");
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }
}
