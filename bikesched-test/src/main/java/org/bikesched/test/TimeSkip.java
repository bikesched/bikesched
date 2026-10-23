// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * its waiting steps. Only jobs no node is running are touched. One transaction locks the waiting
 * steps before their jobs, the order in which publishing an event or ending a child job resumes a
 * waiting job, so the two cannot deadlock; it ends with a notification, so listening nodes poll at
 * once.
 */
final class TimeSkip {

  // The jobs a node may claim: unlocked, not ended.
  private static final String PENDING = "('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING')";

  // The channel the nodes listen on for due jobs; a payload is the job type to poll for.
  private static final String CHANNEL = "bikesched_jobs";

  private static final String FIND_PENDING_JOBS_SQL =
      "select id from bikesched_jobs where type = any(?) and state in " + PENDING;

  private static final String LOCK_WAITING_STEPS_SQL =
      """
            select 1 from bikesched_steps
             where job_id = any(?) and state = 'WAITING'
             order by job_id, name
               for update""";

  // Shifts the jobs still pending by their shift in microseconds (selected by the given
  // statement: id, micros) and returns their id, type and shift.
  private static final String SHIFT_JOBS_SQL =
      """
            update bikesched_jobs j
               set scheduled_at = j.scheduled_at - s.micros * interval '1 microsecond',
                   planned_run_at = j.planned_run_at - s.micros * interval '1 microsecond'
              from (%s) s
             where j.id = s.id and j.state in %s
            returning j.id, j.type, s.micros""";

  // One job, shifted to its next due time; not one waiting without a deadline ('infinity').
  private static final String FAST_FORWARD_JOB_SQL =
      SHIFT_JOBS_SQL.formatted(
          """
          select id, greatest(ceil(extract(epoch from scheduled_at - now()) * 1000000), 0)::bigint
                 as micros
            from bikesched_jobs where id = any(?) and isfinite(scheduled_at)""",
          PENDING);

  private static final String ADVANCE_JOBS_SQL =
      SHIFT_JOBS_SQL.formatted(
          "select id, ?::bigint as micros from bikesched_jobs where id = any(?)", PENDING);

  private static final String SHIFT_STEPS_SQL =
      """
            update bikesched_steps s
               set wake_at = s.wake_at - m.micros * interval '1 microsecond',
                   deadline_at = s.deadline_at - m.micros * interval '1 microsecond'
              from unnest(?::uuid[], ?::bigint[]) as m(id, micros)
             where s.job_id = m.id and s.state = 'WAITING'""";

  private TimeSkip() {}

  static boolean fastForward(DataSource dataSource, UUID jobId) {
    try (Connection connection = dataSource.getConnection()) {
      return inTransaction(
              connection, () -> shift(connection, List.of(jobId), FAST_FORWARD_JOB_SQL, List.of()))
          > 0;
    } catch (SQLException e) {
      throw new BikeschedException("Fast-forwarding job " + jobId + " failed", e);
    }
  }

  static int advance(DataSource dataSource, Duration duration, List<String> types) {
    if (duration.isNegative()) {
      throw new IllegalArgumentException("duration must not be negative: " + duration);
    }
    long micros = duration.toNanos() / 1000;
    try (Connection connection = dataSource.getConnection()) {
      return inTransaction(
          connection,
          () -> {
            var jobIds = new ArrayList<UUID>();
            try (var statement = connection.prepareStatement(FIND_PENDING_JOBS_SQL)) {
              statement.setArray(1, connection.createArrayOf("text", types.toArray()));
              try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                  jobIds.add(rows.getObject(1, UUID.class));
                }
              }
            }
            return shift(connection, jobIds, ADVANCE_JOBS_SQL, List.of(micros));
          });
    } catch (SQLException e) {
      throw new BikeschedException("Advancing the time of " + types + " failed", e);
    }
  }

  /**
   * Locks the waiting steps of the jobs, shifts the jobs that are still pending with {@code
   * shiftJobsSql} (its leading parameters, then the job ids), then their waiting steps by the same
   * amount, and notifies their types. Returns the number of jobs shifted.
   */
  private static int shift(
      Connection connection, List<UUID> jobIds, String shiftJobsSql, List<Object> parameters)
      throws SQLException {
    if (jobIds.isEmpty()) {
      return 0;
    }
    var ids = connection.createArrayOf("uuid", jobIds.toArray());
    try (var statement = connection.prepareStatement(LOCK_WAITING_STEPS_SQL)) {
      statement.setArray(1, ids);
      statement.executeQuery().close();
    }
    var shiftedIds = new ArrayList<UUID>();
    var shiftedMicros = new ArrayList<Long>();
    var types = new LinkedHashSet<String>();
    try (var statement = connection.prepareStatement(shiftJobsSql)) {
      int index = setAll(statement, parameters);
      statement.setArray(index, ids);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          shiftedIds.add(rows.getObject(1, UUID.class));
          types.add(rows.getString(2));
          shiftedMicros.add(rows.getLong(3));
        }
      }
    }
    if (shiftedIds.isEmpty()) {
      return 0;
    }
    try (var statement = connection.prepareStatement(SHIFT_STEPS_SQL)) {
      statement.setArray(1, connection.createArrayOf("uuid", shiftedIds.toArray()));
      statement.setArray(2, connection.createArrayOf("bigint", shiftedMicros.toArray()));
      statement.executeUpdate();
    }
    try (var statement = connection.prepareStatement("select pg_notify(?, ?)")) {
      for (String type : types) {
        statement.setString(1, CHANNEL);
        statement.setString(2, type);
        statement.execute();
      }
    }
    return shiftedIds.size();
  }

  private static int setAll(PreparedStatement statement, List<Object> parameters)
      throws SQLException {
    int index = 1;
    for (Object parameter : parameters) {
      statement.setObject(index++, parameter);
    }
    return index;
  }

  @FunctionalInterface
  private interface Work {
    int run() throws SQLException;
  }

  /** Runs {@code work} in one transaction, restoring the connection's auto-commit afterwards. */
  private static int inTransaction(Connection connection, Work work) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      int result = work.run();
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }
}
