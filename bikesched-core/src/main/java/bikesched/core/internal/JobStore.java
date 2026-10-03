package bikesched.core.internal;

import bikesched.core.BikeschedException;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code bikesched_jobs} table: inserting, claiming and finishing jobs, releasing the jobs of
 * stopped or dead nodes, and the occurrences of recurring jobs. Thread-safe; every call borrows its
 * own connection from the {@link Database}.
 */
final class JobStore {

  private final Database database;

  JobStore(Database database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  private static final String INSERT_JOB_SQL =
      """
            insert into bikesched_jobs (id, job_type, payload, scheduled_at, priority, max_attempts, timeout_millis, schedule, planned_at)
            values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""";

  // Inserts an occurrence of a recurring job unless one is pending already (partial unique index).
  private static final String INSERT_OCCURRENCE_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (job_type) where schedule is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING')
            do nothing""";

  // Moves a pending (not yet running) occurrence to a new schedule; returns the old description.
  private static final String RESCHEDULE_OCCURRENCE_SQL =
      """
            update bikesched_jobs j
               set schedule = ?, scheduled_at = ?, planned_at = ?, updated_at = now()
              from (select id, schedule from bikesched_jobs
                     where job_type = ? and schedule is not null and state = 'SCHEDULED'
                       and schedule <> ?
                     for update) old
             where j.id = old.id
            returning old.schedule""";

  private static final String FIND_PENDING_OCCURRENCE_SQL =
      """
            select id, scheduled_at, state from bikesched_jobs
             where job_type = ? and schedule is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING')""";

  // Running occurrences cannot be deleted; finishing them schedules no successor on nodes that no
  // longer have the recurring job registered.
  private static final String DELETE_PENDING_OCCURRENCES_SQL =
      """
            delete from bikesched_jobs
             where job_type = ? and schedule is not null and state in ('SCHEDULED', 'WAITING')""";

  // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
  // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
  // releases the row locks, so no other node can pick them up afterwards.
  private static final String CLAIM_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1, updated_at = now()
             where id in (select id from bikesched_jobs
                           where state in ('SCHEDULED', 'WAITING') and scheduled_at <= now()
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, job_type, payload::text, priority, scheduled_at, attempts, max_attempts, timeout_millis, schedule, planned_at""";

  private static final String COMPLETE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SUCCEEDED', locked_by = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

  private static final String CANCEL_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'CANCELLED', locked_by = null, locked_at = null, last_error = ?,
                   finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

  // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED. The
  // handler may set a new maximum number of attempts and delay the next attempt (p.*); all
  // expressions see the row's old values, hence the coalesce for the effective maximum.
  private static final String FAIL_JOB_SQL =
      """
            update bikesched_jobs j
               set max_attempts = coalesce(p.max_attempts, j.max_attempts),
                   state = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                then 'FAILED' else 'SCHEDULED' end,
                   finished_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                      then now() end,
                   scheduled_at = case when j.attempts < coalesce(p.max_attempts, j.max_attempts)
                                            and p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else j.scheduled_at end,
                   locked_by = null, locked_at = null, last_error = p.error, updated_at = now()
              from (values (?::text, ?::integer, ?::bigint)) as p (error, max_attempts, retry_after_millis)
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING'
            returning j.state, j.max_attempts, j.scheduled_at""";

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED', locked_by = null, locked_at = null, updated_at = now()
             where locked_by = ? and state = 'RUNNING'""";

  // Releases RUNNING jobs of nodes whose heartbeat is older than the timeout (excluding the
  // calling node) and reports the released job count per dead node. Concurrent callers on other
  // nodes are safe: the second one blocks on the row locks and then no longer matches
  // state = 'RUNNING'.
  private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL =
      """
            with released as (
                update bikesched_jobs j
                   set state = 'SCHEDULED', locked_by = null, locked_at = null, updated_at = now()
                  from bikesched_nodes n
                 where j.locked_by = n.id
                   and j.state = 'RUNNING'
                   and n.id <> ?
                   and n.last_heartbeat_at < now() - make_interval(secs => ?)
                returning n.id as node_id, n.name as node_name)
            select node_id, node_name, count(*) from released group by node_id, node_name""";

  /**
   * Jobs released from a dead node.
   *
   * @param nodeId the dead node
   * @param nodeName its name
   * @param releasedJobs number of jobs put back to {@code SCHEDULED}
   */
  record DeadNodeJobs(UUID nodeId, String nodeName, int releasedJobs) {}

  private static final Comparator<ClaimedJob> CLAIM_ORDER =
      Comparator.comparingInt(ClaimedJob::priority)
          .reversed()
          .thenComparing(ClaimedJob::scheduledAt);

  /**
   * Inserts a new {@code SCHEDULED} job.
   *
   * @param jobId the job id
   * @param job the job's attributes
   */
  void insertJob(UUID jobId, NewJob job) {
    database.update(
        INSERT_JOB_SQL,
        "Scheduling bikesched job of type '" + job.type() + "'",
        insertParameters(jobId, job));
  }

  /**
   * Inserts a new scheduled job using the caller's connection, as part of the caller's transaction:
   * the connection is neither committed nor closed.
   *
   * @param connection the caller's connection
   * @param jobId the new job's id
   * @param job the job's attributes
   */
  void insertJob(Connection connection, UUID jobId, NewJob job) {
    try {
      Database.execute(connection, INSERT_JOB_SQL, insertParameters(jobId, job));
    } catch (SQLException e) {
      throw new BikeschedException(
          "Scheduling bikesched job of type '" + job.type() + "' failed", e);
    }
  }

  private static Object[] insertParameters(UUID jobId, NewJob job) {
    return new Object[] {
      jobId,
      job.type(),
      job.payload(),
      OffsetDateTime.ofInstant(job.scheduledAt(), ZoneOffset.UTC),
      job.priority(),
      job.maxAttempts(),
      job.timeout() == null ? null : job.timeout().toMillis(),
      job.schedule(),
      job.plannedAt() == null ? null : OffsetDateTime.ofInstant(job.plannedAt(), ZoneOffset.UTC)
    };
  }

  /**
   * Claims up to {@code limit} due, scheduled jobs for a node using {@code FOR UPDATE SKIP LOCKED}
   * and marks them {@code RUNNING}.
   *
   * @param nodeId the claiming node
   * @param limit maximum number of jobs to claim
   * @return the claimed jobs, highest priority and earliest scheduled first
   */
  List<ClaimedJob> claimJobs(UUID nodeId, int limit) {
    try (Connection connection = database.connection();
        PreparedStatement claim = connection.prepareStatement(CLAIM_JOBS_SQL)) {
      claim.setObject(1, nodeId);
      claim.setInt(2, limit);
      List<ClaimedJob> jobs = new ArrayList<>();
      try (ResultSet rs = claim.executeQuery()) {
        while (rs.next()) {
          jobs.add(
              new ClaimedJob(
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getInt(4),
                  rs.getObject(5, OffsetDateTime.class).toInstant(),
                  rs.getInt(6),
                  rs.getInt(7),
                  Database.toDuration(rs.getObject(8, Long.class)),
                  rs.getString(9),
                  Database.toInstant(rs.getObject(10, OffsetDateTime.class))));
        }
      }
      Database.commitIfNeeded(connection);
      jobs.sort(CLAIM_ORDER);
      return jobs;
    } catch (SQLException e) {
      throw new BikeschedException("Claiming jobs for bikesched node " + nodeId + " failed", e);
    }
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED}.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @return {@code false} if the job is not running on this node (anymore)
   */
  boolean completeJob(UUID jobId, UUID nodeId) {
    return database.update(COMPLETE_JOB_SQL, "Completing bikesched job " + jobId, jobId, nodeId)
        > 0;
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED} and, in the same transaction, inserts the
   * next occurrence of its recurring job.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param next the next occurrence, {@code null} if none
   * @return empty if the job is not running on this node (anymore); otherwise whether the next
   *     occurrence was inserted (not if one is pending already)
   */
  Optional<Boolean> completeJob(UUID jobId, UUID nodeId, NewJob next) {
    return finishJob("Completing bikesched job " + jobId, next, COMPLETE_JOB_SQL, jobId, nodeId);
  }

  /**
   * Marks a job claimed by the node as {@code CANCELLED}; it is not retried.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param message why the job was cancelled, stored as {@code last_error}; may be {@code null}
   * @return {@code false} if the job is not running on this node (anymore)
   */
  boolean cancelJob(UUID jobId, UUID nodeId, String message) {
    return database.update(
            CANCEL_JOB_SQL, "Cancelling bikesched job " + jobId, message, jobId, nodeId)
        > 0;
  }

  /**
   * Marks a job claimed by the node as {@code CANCELLED} and, in the same transaction, inserts the
   * next occurrence of its recurring job (cancelling cancels this occurrence only).
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param message why the job was cancelled, may be {@code null}
   * @param next the next occurrence, {@code null} if none
   * @return empty if the job is not running on this node (anymore); otherwise whether the next
   *     occurrence was inserted
   */
  Optional<Boolean> cancelJob(UUID jobId, UUID nodeId, String message, NewJob next) {
    return finishJob(
        "Cancelling bikesched job " + jobId, next, CANCEL_JOB_SQL, message, jobId, nodeId);
  }

  /** Runs the update finishing a job and inserts the next occurrence in the same transaction. */
  private Optional<Boolean> finishJob(
      String action, NewJob next, String sql, Object... parameters) {
    if (next == null) {
      return database.update(sql, action, parameters) > 0 ? Optional.of(false) : Optional.empty();
    }
    return database.inTransaction(
        action,
        connection -> {
          if (Database.execute(connection, sql, parameters) == 0) {
            return Optional.empty();
          }
          return Optional.of(insertOccurrence(connection, next));
        });
  }

  /**
   * Inserts an occurrence of a recurring job unless one is pending already.
   *
   * @param occurrence the occurrence
   * @return whether it was inserted
   */
  boolean insertOccurrence(NewJob occurrence) {
    return database.inTransaction(
        "Scheduling an occurrence of recurring job " + occurrence.type(),
        connection -> insertOccurrence(connection, occurrence));
  }

  private static boolean insertOccurrence(Connection connection, NewJob occurrence)
      throws SQLException {
    return Database.execute(
            connection, INSERT_OCCURRENCE_SQL, insertParameters(UUID.randomUUID(), occurrence))
        > 0;
  }

  /**
   * Registers a recurring job: inserts its first occurrence unless one is pending; a pending
   * occurrence of a different schedule (description) that has not started yet is moved to the first
   * run of the new schedule.
   *
   * @param first the first occurrence of the (new) schedule
   * @return the pending occurrence after registering
   */
  RecurringRegistration registerRecurringJob(NewJob first) {
    return database.inTransaction(
        "Registering recurring job " + first.type(),
        connection -> {
          boolean created = insertOccurrence(connection, first);
          String previousSchedule = null;
          if (!created) {
            try (PreparedStatement reschedule =
                connection.prepareStatement(RESCHEDULE_OCCURRENCE_SQL)) {
              reschedule.setString(1, first.schedule());
              reschedule.setObject(
                  2, OffsetDateTime.ofInstant(first.scheduledAt(), ZoneOffset.UTC));
              reschedule.setObject(
                  3,
                  first.plannedAt() == null
                      ? null
                      : OffsetDateTime.ofInstant(first.plannedAt(), ZoneOffset.UTC),
                  Types.TIMESTAMP_WITH_TIMEZONE);
              reschedule.setString(4, first.type());
              reschedule.setString(5, first.schedule());
              try (ResultSet rs = reschedule.executeQuery()) {
                if (rs.next()) {
                  previousSchedule = rs.getString(1);
                }
              }
            }
          }
          try (PreparedStatement find = connection.prepareStatement(FIND_PENDING_OCCURRENCE_SQL)) {
            find.setString(1, first.type());
            try (ResultSet rs = find.executeQuery()) {
              if (!rs.next()) {
                // Cannot happen within this transaction: the insert either succeeded or conflicted.
                throw new IllegalStateException(
                    "No pending occurrence of recurring job " + first.type());
              }
              return new RecurringRegistration(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, OffsetDateTime.class).toInstant(),
                  rs.getString(3),
                  created,
                  previousSchedule);
            }
          }
        });
  }

  /**
   * The pending occurrence of a recurring job after registering it.
   *
   * @param jobId the occurrence's job id
   * @param scheduledAt when it runs (or ran, if already running)
   * @param state its state
   * @param created whether registering created it
   * @param previousSchedule the description of the schedule it was moved away from, {@code null} if
   *     it was not moved
   */
  record RecurringRegistration(
      UUID jobId, Instant scheduledAt, String state, boolean created, String previousSchedule) {}

  /**
   * Deletes the pending occurrences of a recurring job that are not running.
   *
   * @param type the job type of the recurring job
   * @return the number of deleted occurrences
   */
  int deletePendingOccurrences(String type) {
    return database.update(DELETE_PENDING_OCCURRENCES_SQL, "Removing recurring job " + type, type);
  }

  /**
   * Records a failed attempt of a job claimed by the node: the job is scheduled again while
   * attempts remain, otherwise it is marked {@code FAILED}.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param error description of the failure, stored as {@code last_error}
   * @param maxAttempts the job's new maximum number of attempts, {@code null} to keep it
   * @param retryAfter delay of the next attempt from now, {@code null} to retry as soon as possible
   * @return the job after recording the failure; empty if the job is not running on this node
   *     (anymore)
   */
  Optional<FailedJob> failJob(
      UUID jobId, UUID nodeId, String error, Integer maxAttempts, Duration retryAfter) {
    return failJob(jobId, nodeId, error, maxAttempts, retryAfter, null);
  }

  /**
   * Records a failed attempt like {@link #failJob(UUID, UUID, String, Integer, Duration)}; if the
   * job fails permanently, the next occurrence of its recurring job is inserted in the same
   * transaction.
   *
   * @param next the next occurrence, {@code null} if none
   */
  Optional<FailedJob> failJob(
      UUID jobId,
      UUID nodeId,
      String error,
      Integer maxAttempts,
      Duration retryAfter,
      NewJob next) {
    return database.inTransaction(
        "Recording failure of bikesched job " + jobId,
        connection -> {
          Optional<FailedJob> failed =
              failJob(connection, jobId, nodeId, error, maxAttempts, retryAfter);
          if (next != null && failed.isPresent() && !failed.get().retried()) {
            boolean scheduled = insertOccurrence(connection, next);
            return Optional.of(failed.get().withNextOccurrence(scheduled));
          }
          return failed;
        });
  }

  private static Optional<FailedJob> failJob(
      Connection connection,
      UUID jobId,
      UUID nodeId,
      String error,
      Integer maxAttempts,
      Duration retryAfter)
      throws SQLException {
    try (PreparedStatement fail = connection.prepareStatement(FAIL_JOB_SQL)) {
      fail.setString(1, error);
      fail.setObject(2, maxAttempts, Types.INTEGER);
      fail.setObject(3, retryAfter == null ? null : retryAfter.toMillis(), Types.BIGINT);
      fail.setObject(4, jobId);
      fail.setObject(5, nodeId);
      try (ResultSet rs = fail.executeQuery()) {
        return rs.next()
            ? Optional.of(
                new FailedJob(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getObject(3, OffsetDateTime.class).toInstant(),
                    false))
            : Optional.empty();
      }
    }
  }

  /**
   * A job after recording a failed attempt.
   *
   * @param state {@code SCHEDULED} (retried) or {@code FAILED}
   * @param maxAttempts its maximum number of attempts
   * @param scheduledAt when it is retried (only meaningful if {@code SCHEDULED})
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  record FailedJob(
      String state, int maxAttempts, Instant scheduledAt, boolean nextOccurrenceScheduled) {

    boolean retried() {
      return state.equals("SCHEDULED");
    }

    FailedJob withNextOccurrence(boolean scheduled) {
      return new FailedJob(state, maxAttempts, scheduledAt, scheduled);
    }
  }

  /**
   * Puts all jobs still running on the node back to {@code SCHEDULED}, e.g. on shutdown.
   *
   * @param nodeId the node id
   * @return the number of released jobs
   */
  int releaseJobs(UUID nodeId) {
    return database.update(RELEASE_JOBS_SQL, "Releasing jobs of bikesched node " + nodeId, nodeId);
  }

  /**
   * Puts the {@code RUNNING} jobs of dead nodes (no heartbeat within {@code nodeTimeout}) back to
   * {@code SCHEDULED}.
   *
   * @param callingNodeId the node doing the check; never treated as dead
   * @param nodeTimeout heartbeat age after which a node counts as dead
   * @return the dead nodes that had running jobs, with the number of released jobs
   */
  List<DeadNodeJobs> releaseJobsOfDeadNodes(UUID callingNodeId, Duration nodeTimeout) {
    try (Connection connection = database.connection();
        PreparedStatement release = connection.prepareStatement(RELEASE_JOBS_OF_DEAD_NODES_SQL)) {
      release.setObject(1, callingNodeId);
      release.setDouble(2, nodeTimeout.toMillis() / 1000.0);
      List<DeadNodeJobs> released = new ArrayList<>();
      try (ResultSet rs = release.executeQuery()) {
        while (rs.next()) {
          released.add(
              new DeadNodeJobs(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
        }
      }
      Database.commitIfNeeded(connection);
      return released;
    } catch (SQLException e) {
      throw new BikeschedException("Releasing jobs of dead bikesched nodes failed", e);
    }
  }

  /**
   * A job claimed for execution, as stored; becomes a {@link DefaultJob} on the claiming node.
   *
   * @param id the job id
   * @param type the job type
   * @param payload the payload as JSON text, may be {@code null}
   * @param priority its priority
   * @param scheduledAt when it was due
   * @param attempts the number of attempts including the claimed one
   * @param maxAttempts the maximum number of attempts
   * @param timeout its timeout, {@code null} for the node default
   * @param schedule the description of its recurring job's schedule, {@code null} for a one-off job
   * @param plannedAt its planned run if its schedule uses planned times, {@code null} otherwise
   */
  record ClaimedJob(
      UUID id,
      String type,
      String payload,
      int priority,
      Instant scheduledAt,
      int attempts,
      int maxAttempts,
      Duration timeout,
      String schedule,
      Instant plannedAt) {}
}
