// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.bikesched.BikeschedException;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * A connection listening for the notifications of {@link JobStore#notifyDue}, held by one thread
 * that {@link #receive receives} them until it is {@link #close closed}. Closing it from another
 * thread ends a blocked {@link #receive}.
 */
public final class DueJobsListening implements AutoCloseable {

  /** Runs the abort of the connection in the closing thread. */
  private static final Executor INLINE = Runnable::run;

  private final Connection connection;
  private final PGConnection notifications;

  private DueJobsListening(Connection connection, PGConnection notifications) {
    this.connection = connection;
    this.notifications = notifications;
  }

  /**
   * Listens on the channel with the connection, which the returned listening owns (and closes, also
   * if listening fails).
   */
  static DueJobsListening open(Connection connection, String channel) throws SQLException {
    try {
      if (!connection.isWrapperFor(PGConnection.class)) {
        throw new UnsupportedOperationException(
            "The data source's connections are no PostgreSQL JDBC connections and cannot be"
                + " unwrapped to one: "
                + connection.getClass().getName());
      }
      PGConnection notifications = connection.unwrap(PGConnection.class);
      new Sql(connection).execute("listen " + channel);
      if (!connection.getAutoCommit()) {
        connection.commit(); // LISTEN takes effect on commit
      }
      return new DueJobsListening(connection, notifications);
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  /**
   * Waits for notifications.
   *
   * @param timeout how long to wait at most for the first one
   * @return the payloads received, empty if none arrived in time
   * @throws BikeschedException if the connection failed (e.g. it was terminated or closed)
   */
  public List<String> receive(Duration timeout) {
    try {
      PGNotification[] received =
          notifications.getNotifications((int) Math.max(1, Math.min(timeout.toMillis(), 60_000)));
      List<String> payloads = new ArrayList<>();
      if (received != null) {
        for (PGNotification notification : received) {
          payloads.add(notification.getParameter());
        }
      }
      return payloads;
    } catch (SQLException e) {
      throw new BikeschedException("Receiving notifications failed", e);
    }
  }

  /** Ends listening: aborts the connection (ending a blocked {@link #receive}) and closes it. */
  @Override
  public void close() {
    try {
      connection.abort(INLINE);
    } catch (SQLException | RuntimeException e) {
      // closed below
    }
    try {
      connection.close();
    } catch (SQLException | RuntimeException e) {
      // aborted already; nothing left to release
    }
  }
}
