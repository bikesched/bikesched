package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Connection and transaction handling for the stores: runs their {@link Sql} work on a connection
 * of the data source (or on a caller's connection), commits or rolls back, and turns a failing
 * statement into an exception via the caller's {@link SqlExceptionHandler}. Thread-safe; every call
 * borrows its own connection.
 *
 * <p>Writes are committed explicitly if the data source hands out connections with auto-commit
 * disabled. Runtime exceptions thrown by the work are rethrown as they are (after a rollback).
 */
public final class DatabaseAccess {

  private final DataSource dataSource;

  DatabaseAccess(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
   * connection is neither committed nor closed.
   *
   * @param connection the caller's connection
   * @param work the statements
   * @param onFailure creates the exception thrown if a statement fails
   * @return the work's result
   */
  public <T> T onCallerConnection(
      Connection connection, Sql.Work<T> work, SqlExceptionHandler onFailure) {
    try {
      return work.run(new Sql(connection));
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
