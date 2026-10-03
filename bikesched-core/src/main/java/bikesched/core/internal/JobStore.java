package bikesched.core.internal;

import bikesched.core.job.JobState;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
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
            insert into bikesched_jobs (id, job_type, payload, scheduled_at, priority, max_attempts, timeout_millis, schedule, fixed_rate_at)
            values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""";

  // Inserts an occurrence of a recurring job unless one is pending already (partial unique index).
  private static final String INSERT_OCCURRENCE_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (job_type) where schedule is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING')
            do nothing""";

  // Moves a pending (not yet running) occurrence to a new schedule; returns the old description.
  private static final String RESCHEDULE_OCCURRENCE_SQL =
      """
            update bikesched_jobs j
               set schedule = ?, scheduled_at = ?, fixed_rate_at = ?, updated_at = now()
              from (select id, schedule from bikesched_jobs
                     where job_type = ? and schedule is not null and state = 'SCHEDULED'
                       and schedule <> ?
                     for update) old
             where j.id = old.id
            returning old.schedule""";

  private static final String FIND_PENDING_OCCURRENCE_SQL =
      """
            select id, scheduled_at, state from bikesched_jobs
             where job_type = ? and schedule is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING')""";

  // Running occurrences cannot be deleted; finishing them schedules no successor on nodes that no
  // longer have the recurring job registered.
  private static final String DELETE_PENDING_OCCURRENCES_SQL =
      """
            delete from bikesched_jobs
             where job_type = ? and schedule is not null and state in ('SCHEDULED', 'WAITING')""";

  // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
  // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
  // releases the row locks, so no other node can pick them up afterwards. Claiming a COMPENSATING
  // job counts a compensation attempt instead of an attempt (compensated_state stays set).
  private static final String CLAIM_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'RUNNING', locked_by = ?, locked_at = now(),
                   attempts = attempts + case when compensated_state is null then 1 else 0 end,
                   compensation_attempts = compensation_attempts + case when compensated_state is null then 0 else 1 end,
                   updated_at = now()
             where id in (select id from bikesched_jobs
                           where state in ('SCHEDULED', 'WAITING', 'COMPENSATING') and scheduled_at <= now()
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, job_type, payload::text, priority, scheduled_at, attempts, max_attempts, timeout_millis, schedule, fixed_rate_at,
                      compensated_state, compensation_attempts, cancel_requested_at is not null, cancel_message""";

  private static final String FIND_JOB_SQL =
      """
            select job_type, state from bikesched_jobs where id = ?""";

  private static final String COMPLETE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SUCCEEDED', locked_by = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING'""";

  // Whether a job has completed steps to compensate; joined into the statements ending a job.
  private static final String COMPENSATE_SQL =
      """
            (select exists (select 1 from bikesched_steps
                             where job_id = ? and compensable and state = 'SUCCEEDED') as compensate) c""";

  // A cancelled job ends CANCELLED, or is COMPENSATING first if it has steps to compensate. A job
  // cancelled on request without running its handler gives back the attempt its claim counted.
  private static final String CANCEL_JOB_SQL =
      """
            update bikesched_jobs j
               set state = case when c.compensate then 'COMPENSATING' else 'CANCELLED' end,
                   compensated_state = case when c.compensate then 'CANCELLED' end,
                   scheduled_at = case when c.compensate then now() else j.scheduled_at end,
                   finished_at = case when c.compensate then null else now() end,
                   attempts = j.attempts - ?,
                   locked_by = null, locked_at = null, last_error = ?, updated_at = now()
              from %s
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING' and j.compensated_state is null
            returning j.state"""
          .formatted(COMPENSATE_SQL);

  // The unfinished steps of a job being cancelled by this node (waiting for an event, or left
  // running by a crash) are cancelled too: they no longer receive events. Runs before
  // CANCEL_JOB_SQL in its transaction, locking the steps before the job like publishing an event
  // does (EventStore.RESUME_WAITING_STEPS_SQL), so the two cannot deadlock.
  private static final String CANCEL_STEPS_SQL =
      """
            update bikesched_steps set state = 'CANCELLED', finished_at = now()
             where job_id = ? and state in ('WAITING', 'RUNNING')
               and exists (select 1 from bikesched_jobs
                            where id = ? and locked_by = ? and state = 'RUNNING' and compensated_state is null)""";

  // Bikesched.cancel(jobId): marks the job; the node claiming it next cancels it instead of running
  // its handler. Scheduled and waiting jobs are due right away; a running job is cancelled after
  // its current attempt, unless that attempt completes it (or fails it permanently).
  private static final String REQUEST_CANCEL_SQL =
      """
            update bikesched_jobs
               set cancel_requested_at = coalesce(cancel_requested_at, now()), cancel_message = ?,
                   scheduled_at = case when state in ('SCHEDULED', 'WAITING') then now() else scheduled_at end,
                   updated_at = now()
             where id = ? and state in ('SCHEDULED', 'WAITING', 'RUNNING') and compensated_state is null""";

  // All compensations of a compensating job ran: it ends in the state it was compensated for.
  private static final String COMPLETE_COMPENSATION_SQL =
      """
            update bikesched_jobs
               set state = compensated_state, locked_by = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and locked_by = ? and state = 'RUNNING' and compensated_state is not null
            returning state""";

  // A failed compensation attempt: compensating again (after the delay, if any) while compensation
  // attempts remain (max_attempts applies to them as well), otherwise COMPENSATION_FAILED.
  private static final String FAIL_COMPENSATION_SQL =
      """
            update bikesched_jobs j
               set state = case when j.compensation_attempts >= j.max_attempts
                                then 'COMPENSATION_FAILED' else 'COMPENSATING' end,
                   finished_at = case when j.compensation_attempts >= j.max_attempts then now() end,
                   scheduled_at = case when j.compensation_attempts < j.max_attempts
                                            and p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else now() end,
                   last_error = case when j.compensation_attempts >= j.max_attempts
                                     then p.error else j.last_error end,
                   locked_by = null, locked_at = null, updated_at = now()
              from (values (?::text, ?::bigint)) as p (error, retry_after_millis)
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING' and j.compensated_state is not null
            returning j.state, j.max_attempts, j.scheduled_at""";

  // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED, or
  // COMPENSATING first if it has steps to compensate. The handler may set a new maximum number of
  // attempts and delay the next attempt (p.*); all expressions see the row's old values, hence the
  // coalesce for the effective maximum. A retry of a job whose cancellation was requested is due
  // right away (and then cancelled).
  private static final String FAIL_JOB_SQL =
      """
            update bikesched_jobs j
               set max_attempts = coalesce(p.max_attempts, j.max_attempts),
                   state = case when j.attempts < coalesce(p.max_attempts, j.max_attempts) then 'SCHEDULED'
                                when c.compensate then 'COMPENSATING' else 'FAILED' end,
                   compensated_state = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                                 and c.compensate then 'FAILED' end,
                   finished_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                           and not c.compensate then now() end,
                   scheduled_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                       then case when c.compensate then now() else j.scheduled_at end
                                       when j.cancel_requested_at is not null then now()
                                       when p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else j.scheduled_at end,
                   locked_by = null, locked_at = null, last_error = p.error, updated_at = now()
              from (values (?::text, ?::integer, ?::bigint)) as p (error, max_attempts, retry_after_millis),
                   %s
             where j.id = ? and j.locked_by = ? and j.state = 'RUNNING' and j.compensated_state is null
            returning j.state, j.max_attempts, j.scheduled_at"""
          .formatted(COMPENSATE_SQL);

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = case when compensated_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by = null, locked_at = null, updated_at = now()
             where locked_by = ? and state = 'RUNNING'""";

  // Releases RUNNING jobs (back to SCHEDULED, or COMPENSATING) of nodes whose heartbeat is older
  // than the timeout (excluding the
  // calling node) and reports the released job count per dead node. Concurrent callers on other
  // nodes are safe: the second one blocks on the row locks and then no longer matches
  // state = 'RUNNING'.
  private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL =
      """
            with released as (
                update bikesched_jobs j
                   set state = case when j.compensated_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                       locked_by = null, locked_at = null, updated_at = now()
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
    Database.onCallerConnection(
        connection,
        "Scheduling bikesched job of type '" + job.type() + "'",
        db -> db.update(INSERT_JOB_SQL, insertParameters(jobId, job)));
  }

  private static Object[] insertParameters(UUID jobId, NewJob job) {
    return new Object[] {
      jobId,
      job.type(),
      job.payload(),
      job.scheduledAt(),
      job.priority(),
      job.maxAttempts(),
      Sql.millis(job.timeout()),
      job.schedule(),
      Sql.timestamp(job.fixedRateAt())
    };
  }

  /**
   * Reads a job in a read-only transaction.
   *
   * @param jobId the job id
   * @return the job's id, type and state; empty if there is no such job
   */
  Optional<DefaultJobInfo> findJob(UUID jobId) {
    return database.inReadOnlyTransaction(
        "Reading bikesched job " + jobId,
        db ->
            db.one(
                FIND_JOB_SQL,
                row -> new DefaultJobInfo(jobId, row.string(1), JobState.valueOf(row.string(2))),
                jobId));
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
    List<ClaimedJob> jobs =
        database.withConnection(
            "Claiming jobs for bikesched node " + nodeId,
            db -> db.list(CLAIM_JOBS_SQL, ClaimedJob::from, nodeId, limit));
    jobs.sort(CLAIM_ORDER);
    return jobs;
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
    return cancelJob(jobId, nodeId, message, true, null).isPresent();
  }

  /**
   * Cancels a job claimed by the node: marks it {@code CANCELLED}, or {@code COMPENSATING} if it
   * has completed steps to compensate, and its unfinished steps {@code CANCELLED}. If the job ends
   * {@code CANCELLED}, the next occurrence of its recurring job is inserted in the same transaction
   * (cancelling cancels this occurrence only).
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param message why the job was cancelled, may be {@code null}
   * @param attemptRan whether the handler ran in the claimed attempt; if not (cancelled on request
   *     right after claiming it), the attempt is not counted
   * @param next the next occurrence, {@code null} if none
   * @return the job's new state; empty if the job is not running on this node (anymore)
   */
  Optional<EndedJob> cancelJob(
      UUID jobId, UUID nodeId, String message, boolean attemptRan, NewJob next) {
    return database.inTransaction(
        "Cancelling bikesched job " + jobId,
        db -> {
          db.update(CANCEL_STEPS_SQL, jobId, jobId, nodeId);
          Optional<String> state =
              db.one(
                  CANCEL_JOB_SQL,
                  row -> row.string(1),
                  attemptRan ? 0 : 1,
                  message,
                  jobId,
                  jobId,
                  nodeId);
          if (state.isEmpty()) {
            return Optional.empty();
          }
          boolean scheduled =
              next != null && state.get().equals("CANCELLED") && insertOccurrence(db, next);
          return Optional.of(new EndedJob(state.get(), scheduled));
        });
  }

  /**
   * A job after its attempt ended it, or started compensating it.
   *
   * @param state its new state, e.g. {@code CANCELLED} or {@code COMPENSATING}
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  record EndedJob(String state, boolean nextOccurrenceScheduled) {

    boolean compensating() {
      return state.equals("COMPENSATING");
    }
  }

  /**
   * Requests the cancellation of a job: the node claiming it next cancels it instead of running its
   * handler (compensating its steps, if any). Scheduled and waiting jobs are due right away; a
   * running job is cancelled after its current attempt unless that attempt ends it.
   *
   * @param jobId the job id
   * @param message why the job is cancelled, may be {@code null}
   * @return {@code false} if the job does not exist, has ended or is compensating
   */
  boolean requestCancel(UUID jobId, String message) {
    return database.update(REQUEST_CANCEL_SQL, "Cancelling bikesched job " + jobId, message, jobId)
        > 0;
  }

  /**
   * Ends a compensating job claimed by the node, once all its compensations ran, in the state it
   * was compensated for ({@code CANCELLED} or {@code FAILED}); inserts the next occurrence of its
   * recurring job in the same transaction.
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param next the next occurrence, {@code null} if none
   * @return the job's new state; empty if the job is not compensating on this node (anymore)
   */
  Optional<EndedJob> completeCompensation(UUID jobId, UUID nodeId, NewJob next) {
    return database.inTransaction(
        "Completing the compensation of bikesched job " + jobId,
        db -> {
          Optional<String> state =
              db.one(COMPLETE_COMPENSATION_SQL, row -> row.string(1), jobId, nodeId);
          if (state.isEmpty()) {
            return Optional.empty();
          }
          boolean scheduled = next != null && insertOccurrence(db, next);
          return Optional.of(new EndedJob(state.get(), scheduled));
        });
  }

  /**
   * Records a failed compensation attempt of a job claimed by the node: compensating again while
   * compensation attempts remain, otherwise {@code COMPENSATION_FAILED} (then inserting the next
   * occurrence of its recurring job in the same transaction).
   *
   * @param jobId the job id
   * @param nodeId the node that claimed the job
   * @param error description of the failure, stored as {@code last_error} once final
   * @param retryAfter delay of the next compensation attempt, {@code null} for as soon as possible
   * @param next the next occurrence, {@code null} if none
   * @return the job after recording the failure; empty if it is not compensating on this node
   */
  Optional<FailedJob> failCompensation(
      UUID jobId, UUID nodeId, String error, Duration retryAfter, NewJob next) {
    return database.inTransaction(
        "Recording the failed compensation of bikesched job " + jobId,
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_COMPENSATION_SQL,
                  FailedJob::from,
                  error,
                  Sql.millis(retryAfter),
                  jobId,
                  nodeId);
          if (next != null && failed.isPresent() && failed.get().ended()) {
            return Optional.of(failed.get().withNextOccurrence(insertOccurrence(db, next)));
          }
          return failed;
        });
  }

  /** Runs the update finishing a job and inserts the next occurrence in the same transaction. */
  private Optional<Boolean> finishJob(
      String action, NewJob next, String statement, Object... parameters) {
    if (next == null) {
      return database.update(statement, action, parameters) > 0
          ? Optional.of(false)
          : Optional.empty();
    }
    return database.inTransaction(
        action,
        db -> {
          if (db.update(statement, parameters) == 0) {
            return Optional.empty();
          }
          return Optional.of(insertOccurrence(db, next));
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
        db -> insertOccurrence(db, occurrence));
  }

  private static boolean insertOccurrence(Sql db, NewJob occurrence) throws SQLException {
    return db.update(INSERT_OCCURRENCE_SQL, insertParameters(UUID.randomUUID(), occurrence)) > 0;
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
        db -> {
          boolean created = insertOccurrence(db, first);
          String previousSchedule =
              created
                  ? null
                  : db.one(
                          RESCHEDULE_OCCURRENCE_SQL,
                          row -> row.string(1),
                          first.schedule(),
                          first.scheduledAt(),
                          Sql.timestamp(first.fixedRateAt()),
                          first.type(),
                          first.schedule())
                      .orElse(null);
          return db.one(
                  FIND_PENDING_OCCURRENCE_SQL,
                  row ->
                      new RecurringRegistration(
                          row.uuid(1), row.instant(2), row.string(3), created, previousSchedule),
                  first.type())
              // Cannot happen within this transaction: the insert either succeeded or conflicted.
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No pending occurrence of recurring job " + first.type()));
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
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_JOB_SQL,
                  FailedJob::from,
                  error,
                  Sql.typed(maxAttempts, Types.INTEGER),
                  Sql.millis(retryAfter),
                  jobId,
                  jobId,
                  nodeId);
          if (next != null && failed.isPresent() && failed.get().ended()) {
            boolean scheduled = insertOccurrence(db, next);
            return Optional.of(failed.get().withNextOccurrence(scheduled));
          }
          return failed;
        });
  }

  /**
   * A job after recording a failed attempt or compensation attempt.
   *
   * @param state {@code SCHEDULED} (retried), {@code COMPENSATING} (compensating now, or again),
   *     {@code FAILED} or {@code COMPENSATION_FAILED}
   * @param maxAttempts its maximum number of (compensation) attempts
   * @param scheduledAt when it runs next (only meaningful unless it ended)
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  record FailedJob(
      String state, int maxAttempts, Instant scheduledAt, boolean nextOccurrenceScheduled) {

    boolean retried() {
      return state.equals("SCHEDULED");
    }

    boolean compensating() {
      return state.equals("COMPENSATING");
    }

    /** Whether the job ended ({@code FAILED} or {@code COMPENSATION_FAILED}). */
    boolean ended() {
      return state.equals("FAILED") || state.equals("COMPENSATION_FAILED");
    }

    /** Maps a row of {@code FAIL_JOB_SQL} or {@code FAIL_COMPENSATION_SQL}. */
    static FailedJob from(Sql.Row row) throws SQLException {
      return new FailedJob(row.string(1), row.integer(2), row.instant(3), false);
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
    return database.withConnection(
        "Releasing jobs of dead bikesched nodes",
        db ->
            db.list(
                RELEASE_JOBS_OF_DEAD_NODES_SQL,
                row -> new DeadNodeJobs(row.uuid(1), row.string(2), row.integer(3)),
                callingNodeId,
                Sql.seconds(nodeTimeout)));
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
   * @param fixedRateAt its planned run if its schedule is fixed-rate, {@code null} otherwise
   * @param compensatedState {@code CANCELLED} or {@code FAILED} if the job was claimed to
   *     compensate its steps, {@code null} if it was claimed to run
   * @param compensationAttempts the number of compensation attempts including the claimed one
   * @param cancelRequested whether its cancellation was requested via {@code Bikesched.cancel}
   * @param cancelMessage the message of that request, may be {@code null}
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
      Instant fixedRateAt,
      String compensatedState,
      int compensationAttempts,
      boolean cancelRequested,
      String cancelMessage) {

    /** Maps a row of {@code CLAIM_JOBS_SQL}. */
    static ClaimedJob from(Sql.Row row) throws SQLException {
      return new ClaimedJob(
          row.uuid(1),
          row.string(2),
          row.string(3),
          row.integer(4),
          row.instant(5),
          row.integer(6),
          row.integer(7),
          row.millis(8),
          row.string(9),
          row.instant(10),
          row.string(11),
          row.integer(12),
          row.bool(13),
          row.string(14));
    }
  }
}
