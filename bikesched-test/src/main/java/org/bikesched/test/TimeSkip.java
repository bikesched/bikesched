// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.BikeschedException;

/**
 * Skips time for pending jobs, behind {@link BikeschedTest#fastForward} and {@link
 * BikeschedTest#advanceTime}. The database decides with {@code now()} whether a job or a waiting
 * step is due, so instead of moving the clock forward, this moves the stored times back: a job's
 * {@code scheduled_at} and {@code planned_run_at}, and {@code wake_at} and {@code deadline_at} of
 * its waiting steps. Only jobs no node is running are touched; each statement ends with a
 * notification, so listening nodes poll at once.
 */
final class TimeSkip {

  // The jobs a node may claim: unlocked, not ended.
  private static final String PENDING = "('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING')";

  // The channel the nodes listen on for due jobs; a payload is the job type to poll for.
  private static final String CHANNEL = "bikesched_jobs";

  // Shifts the selected jobs (id, type and the interval to shift by) and their waiting steps back,
  // returning the types of the shifted jobs. The rows stay locked until the statement commits, so a
  // node cannot claim a job halfway.
  private static final String SHIFT_SQL =
      """
            with shift as (%s),
            steps as (
              update bikesched_steps s
                 set wake_at = s.wake_at - shift.by, deadline_at = s.deadline_at - shift.by
                from shift
               where s.job_id = shift.id and s.state = 'WAITING'
            )
            update bikesched_jobs j
               set scheduled_at = j.scheduled_at - shift.by,
                   planned_run_at = j.planned_run_at - shift.by
              from shift
             where j.id = shift.id
            returning j.type""";

  // One job, shifted to its next due time; not one waiting without a deadline ('infinity').
  private static final String FAST_FORWARD_SQL =
      SHIFT_SQL.formatted(
          """
          select id, greatest(scheduled_at - now(), interval '0') as by
            from bikesched_jobs
           where id = ? and state in %s and isfinite(scheduled_at)
             for update"""
              .formatted(PENDING));

  // The pending jobs of the given types, shifted by the given number of microseconds.
  private static final String ADVANCE_SQL =
      SHIFT_SQL.formatted(
          """
          select id, ? * interval '1 microsecond' as by
            from bikesched_jobs
           where type = any(?) and state in %s
           order by id
             for update"""
              .formatted(PENDING));

  private TimeSkip() {}

  static boolean fastForward(DataSource dataSource, UUID jobId) {
    try {
      return !shift(dataSource, FAST_FORWARD_SQL, jobId).isEmpty();
    } catch (SQLException e) {
      throw new BikeschedException("Fast-forwarding job " + jobId + " failed", e);
    }
  }

  static int advance(DataSource dataSource, Duration duration, List<String> types) {
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    try (Connection connection = dataSource.getConnection()) {
      Object typeArray = connection.createArrayOf("text", types.toArray());
      return shift(connection, ADVANCE_SQL, duration.toNanos() / 1000, typeArray).size();
    } catch (SQLException e) {
      throw new BikeschedException("Advancing the time of " + types + " failed", e);
    }
  }

  private static List<String> shift(DataSource dataSource, String sql, Object... parameters)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      return shift(connection, sql, parameters);
    }
  }

  /** Runs a shift and notifies the shifted jobs' types; returns the type of each shifted job. */
  private static List<String> shift(Connection connection, String sql, Object... parameters)
      throws SQLException {
    var types = new ArrayList<String>();
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          types.add(rows.getString(1));
        }
      }
    }
    if (!types.isEmpty()) {
      try (var statement = connection.prepareStatement("select pg_notify(?, ?)")) {
        for (String type : new LinkedHashSet<>(types)) {
          statement.setString(1, CHANNEL);
          statement.setString(2, type);
          statement.execute();
        }
      }
    }
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
    return types;
  }
}
