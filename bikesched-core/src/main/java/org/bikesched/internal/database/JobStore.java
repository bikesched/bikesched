// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.JsonMapper;
import org.bikesched.internal.database.ClaimEnd.FollowUp;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_jobs} table: inserting, claiming and finishing jobs, releasing the jobs of
 * stopped or dead nodes, the occurrences of recurring jobs, and child jobs (started as steps of
 * their parent, whose step receives their outcome when they end). Changes are recorded in the
 * {@link HistoryStore job history}, if enabled. Thread-safe; every call borrows its own connection
 * from the {@link DatabaseAccess}.
 */
public final class JobStore {

  private final DatabaseAccess access;
  private final HistoryStore history;
  private final JsonMapper jsonMapper;

  JobStore(DatabaseAccess access, HistoryStore history, JsonMapper jsonMapper) {
    this.access = access;
    this.history = history;
    this.jsonMapper = jsonMapper;
  }

  private static final String INSERT_JOB_SQL =
      """
            insert into bikesched_jobs (id, type, payload, scheduled_at, priority, max_attempts, timeout_millis, recurrence, planned_run_at, idempotency_key)
            values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""";

  // Inserts a job with an idempotency key unless a job of its type has the key (partial unique
  // index); FIND_KEYED_JOB_SQL then finds that job.
  private static final String INSERT_KEYED_JOB_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (type, idempotency_key) where idempotency_key is not null do nothing""";

  private static final String FIND_KEYED_JOB_SQL =
      "select id from bikesched_jobs where type = ? and idempotency_key = ?";

  private static final String INSERT_CHILD_JOB_SQL =
      """
            insert into bikesched_jobs (id, type, payload, scheduled_at, priority, max_attempts, timeout_millis, parent_job_id)
            values (?, ?, ?::jsonb, ?, ?, ?, ?, ?)""";

  // The step of a job starting a child job.
  private static final String FIND_CHILD_JOB_STEP_SQL =
      """
            select id, type, state, child_job_id, child_job_type
              from bikesched_steps
             where job_id = ? and name = ?
               for update""";

  private static final String INSERT_CHILD_JOB_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, child_job_id, child_job_type, compensable)
            values (?, ?, ?, 'CHILD_JOB', 'WAITING', 1, ?, ?, ?)""";

  // A compensated child job step runs again when its job is retried: it starts a new child of the
  // same type.
  private static final String RESTART_CHILD_JOB_STEP_SQL =
      """
            update bikesched_steps
               set state = 'WAITING', child_job_id = ?, child_end_state = null, result = null, last_error = null,
                   wake_at = null, compensable = ?, attempts = attempts + 1, started_at = now(), finished_at = null
             where id = ? and type = 'CHILD_JOB' and state = 'COMPENSATED'""";

  // A child job ended: its outcome completes the parent's step waiting for it, in the child's
  // transaction and without the parent's lock (like publishing an event completes other jobs'
  // waits). A CANCELLED or TIMED_OUT step stays as it is. Waits for the parent's suspension if it
  // locked the step (StepStore.suspendAtChildJob), so RESUME_PARENT_SQL, a statement of its own
  // with a new snapshot, then sees the parent WAITING.
  private static final String COMPLETE_CHILD_JOB_STEP_SQL =
      """
            update bikesched_steps s
               set state = 'SUCCEEDED', child_end_state = c.state,
                   result = case when c.state = 'SUCCEEDED' then c.result end,
                   last_error = case when c.state = 'SUCCEEDED' then null else c.last_error end,
                   finished_at = now()
              from bikesched_jobs c
             where c.id = ? and s.job_id = c.parent_job_id and s.child_job_id = c.id
               and s.type = 'CHILD_JOB' and s.state = 'WAITING'
            returning s.job_id, s.name, c.state""";

  // A WAITING parent is due again (it may wait for another step: it replays and suspends again); a
  // RUNNING one is left alone, its wait or suspension finds the step completed.
  private static final String RESUME_PARENT_SQL =
      """
            update bikesched_jobs set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
             where id = ? and state = 'WAITING'
            returning state, attempts, type, scheduled_at""";

  // Inserts an occurrence of a recurring job unless one is pending already (partial unique index).
  private static final String INSERT_OCCURRENCE_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (type) where recurrence is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')
            do nothing""";

  // Moves a pending (not yet running) occurrence to a new recurrence; returns the old description.
  private static final String RESCHEDULE_OCCURRENCE_SQL =
      """
            update bikesched_jobs j
               set recurrence = ?, scheduled_at = ?, planned_run_at = ?, updated_at = now()
              from (select id, recurrence from bikesched_jobs
                     where type = ? and recurrence is not null and state = 'SCHEDULED'
                       and recurrence <> ?
                     for update) old
             where j.id = old.id
            returning old.recurrence, j.id, j.attempts""";

  private static final String FIND_PENDING_OCCURRENCE_SQL =
      """
            select id, scheduled_at, state from bikesched_jobs
             where type = ? and recurrence is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')""";

  // Running occurrences cannot be deleted; finishing them schedules no successor on nodes that no
  // longer have the recurring job registered.
  private static final String DELETE_PENDING_OCCURRENCES_SQL =
      """
            delete from bikesched_jobs
             where type = ? and recurrence is not null and state in ('SCHEDULED', 'WAITING')
            returning id, state, attempts""";

  // The jobs among the given ones whose poll waits for its next check.
  private static final String WAITING_AT_POLL_SQL =
      """
            select job_id from bikesched_steps
             where job_id = any(?) and type = 'POLL' and state = 'WAITING'""";

  // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
  // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
  // releases the row locks, so no other node can pick them up afterwards. Claiming a COMPENSATING
  // job counts a compensation attempt instead of an attempt (compensation_end_state stays set),
  // claiming
  // a FAILING job a run of its failure handler (failure_end_state stays set). Only jobs of types
  // the
  // node has handlers for: during a rolling deploy, nodes of the old version leave
  // jobs of new types to the new ones (the type is a key column of the polling index, so the filter
  // is checked on index entries, in claim order, without visiting other rows).
  private static final String CLAIM_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'RUNNING', locked_by_node_id = ?, lock_token = gen_random_uuid(),
                   locked_at = now(),
                   attempts = attempts
                       + case when compensation_end_state is null and failure_end_state is null
                              then 1 else 0 end,
                   compensation_attempts = compensation_attempts
                       + case when compensation_end_state is not null and failure_end_state is null
                              then 1 else 0 end,
                   failure_attempts = failure_attempts
                       + case when failure_end_state is null then 0 else 1 end,
                   updated_at = now()
             where id in (select id from bikesched_jobs
                           where state in ('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING') and scheduled_at <= now()
                             and type = any(?)
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, type, payload::text, priority, scheduled_at, attempts, max_attempts,
                      timeout_millis, recurrence, planned_run_at, compensation_end_state,
                      compensation_attempts, cancel_requested_at is not null, cancel_message,
                      locked_by_node_id, lock_token, parent_job_id, failure_end_state,
                      failure_attempts, last_error""";

  private static final String FIND_JOB_SQL =
      """
            select type, state, result::text, parent_job_id from bikesched_jobs where id = ?""";

  private static final String COMPLETE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SUCCEEDED', result = ?::jsonb, locked_by_node_id = null, lock_token = null, locked_at = null,
                   finished_at = now(), updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING' and failure_end_state is null
            returning attempts""";

  // Whether a job has completed steps to compensate; joined into the statements ending a job. A
  // child job step counts once the child was started, also while it runs (cancelling the job
  // cancels the step, a timed-out wait leaves it TIMED_OUT).
  private static final String COMPENSATE_SQL =
      """
            (select exists (select 1 from bikesched_steps
                             where job_id = ? and compensable
                               and (state = 'SUCCEEDED'
                                    or type = 'CHILD_JOB' and state in ('WAITING', 'CANCELLED', 'TIMED_OUT'))) as compensate) c""";

  // A cancelled job ends CANCELLED, or is COMPENSATING first if it has steps to compensate. A job
  // cancelled on request without running its handler gives back the attempt its claim counted.
  private static final String CANCEL_JOB_SQL =
      """
            update bikesched_jobs j
               set state = case when c.compensate then 'COMPENSATING' else 'CANCELLED' end,
                   compensation_end_state = case when c.compensate then 'CANCELLED' end,
                   scheduled_at = case when c.compensate then now() else j.scheduled_at end,
                   finished_at = case when c.compensate then null else now() end,
                   attempts = j.attempts - ?,
                   locked_by_node_id = null, lock_token = null, locked_at = null, last_error = ?, updated_at = now()
              from %s
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensation_end_state is null
               and j.failure_end_state is null
            returning j.state, j.attempts, j.type, j.scheduled_at"""
          .formatted(COMPENSATE_SQL);

  // The unfinished steps of a job being cancelled by this node (waiting for an event, or left
  // running by a crash) are cancelled too: they no longer receive events. Runs before
  // CANCEL_JOB_SQL in its transaction, locking the steps before the job like publishing an event
  // does (EventStore.RESUME_WAITING_STEPS_SQL), so the two cannot deadlock.
  private static final String CANCEL_STEPS_SQL =
      """
            update bikesched_steps set state = 'CANCELLED', locked_by_node_id = null, finished_at = now()
             where job_id = ? and state in ('WAITING', 'RUNNING')
               and exists (select 1 from bikesched_jobs
                            where id = ? and lock_token = ? and state = 'RUNNING' and compensation_end_state is null
                              and failure_end_state is null)
            returning name""";

  // Bikesched.retry(jobId): a FAILED job runs again with all its attempts, keeping its completed
  // steps (compensated ones are COMPENSATED and run again). Not for occurrences of recurring jobs
  // and child jobs (FIND_NOT_RETRYABLE_SQL tells them apart when nothing was retried).
  private static final String RETRY_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED', attempts = 0, compensation_end_state = null, compensation_attempts = 0,
                   failure_attempts = 0, cancel_requested_at = null, cancel_message = null,
                   scheduled_at = now(), finished_at = null, updated_at = now()
             where id = ? and state = 'FAILED' and recurrence is null and parent_job_id is null
            returning state, attempts, type, scheduled_at""";

  private static final String FIND_NOT_RETRYABLE_SQL =
      """
            select type, parent_job_id from bikesched_jobs
             where id = ? and (recurrence is not null or parent_job_id is not null)""";

  // Bikesched.cancel(jobId): marks the job; the node claiming it next cancels it instead of running
  // its handler. Scheduled and waiting jobs are due right away; a running job is cancelled after
  // its current attempt, unless that attempt completes it (or fails it permanently).
  private static final String REQUEST_CANCEL_SQL =
      """
            update bikesched_jobs
               set cancel_requested_at = coalesce(cancel_requested_at, now()), cancel_message = ?,
                   scheduled_at = case when state in ('SCHEDULED', 'WAITING') then now() else scheduled_at end,
                   updated_at = now()
             where id = ? and state in ('SCHEDULED', 'WAITING', 'RUNNING') and compensation_end_state is null
               and failure_end_state is null
            returning state, attempts, type, scheduled_at""";

  // All compensations of a compensating job ran: it ends in the state it was compensated for, or
  // is FAILING first if it failed and its type has a failure handler on this node (p.*).
  private static final String COMPLETE_COMPENSATION_SQL =
      """
            update bikesched_jobs j
               set state = case when j.compensation_end_state = 'FAILED' and p.failure_handler then 'FAILING'
                                else j.compensation_end_state end,
                   failure_end_state = case when j.compensation_end_state = 'FAILED' and p.failure_handler then 'FAILED' end,
                   scheduled_at = case when j.compensation_end_state = 'FAILED' and p.failure_handler then now()
                                       else j.scheduled_at end,
                   finished_at = case when j.compensation_end_state = 'FAILED' and p.failure_handler then null else now() end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
              from (values (?::boolean)) as p (failure_handler)
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensation_end_state is not null
               and j.failure_end_state is null
            returning j.state, j.attempts, j.type, j.scheduled_at""";

  // A failed compensation attempt: compensating again (after the delay, if any) while compensation
  // attempts remain (max_attempts applies to them as well), otherwise COMPENSATION_FAILED, or
  // FAILING first if its type has a failure handler on this node.
  private static final String FAIL_COMPENSATION_SQL =
      """
            update bikesched_jobs j
               set state = case when j.compensation_attempts < j.max_attempts then 'COMPENSATING'
                                when p.failure_handler then 'FAILING' else 'COMPENSATION_FAILED' end,
                   failure_end_state = case when j.compensation_attempts >= j.max_attempts and p.failure_handler
                                        then 'COMPENSATION_FAILED' end,
                   finished_at = case when j.compensation_attempts >= j.max_attempts and not p.failure_handler
                                      then now() end,
                   scheduled_at = case when j.compensation_attempts < j.max_attempts
                                            and p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else now() end,
                   last_error = case when j.compensation_attempts >= j.max_attempts
                                     then p.error else j.last_error end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
              from (values (?::text, ?::bigint, ?::boolean)) as p (error, retry_after_millis, failure_handler)
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensation_end_state is not null
               and j.failure_end_state is null
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts, j.type""";

  // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED, or
  // COMPENSATING first if it has steps to compensate, or FAILING first if its type has a failure
  // handler on this node. The handler may set a new maximum number of
  // attempts and delay the next attempt (p.*); all expressions see the row's old values, hence the
  // coalesce for the effective maximum. A retry of a job whose cancellation was requested is due
  // right away (and then cancelled).
  private static final String FAIL_JOB_SQL =
      """
            update bikesched_jobs j
               set max_attempts = coalesce(p.max_attempts, j.max_attempts),
                   state = case when j.attempts < coalesce(p.max_attempts, j.max_attempts) then 'SCHEDULED'
                                when c.compensate then 'COMPENSATING'
                                when p.failure_handler then 'FAILING' else 'FAILED' end,
                   compensation_end_state = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                                 and c.compensate then 'FAILED' end,
                   failure_end_state = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                             and not c.compensate and p.failure_handler then 'FAILED' end,
                   finished_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                           and not c.compensate and not p.failure_handler then now() end,
                   scheduled_at = case when j.attempts >= coalesce(p.max_attempts, j.max_attempts)
                                       then case when c.compensate or p.failure_handler then now()
                                                 else j.scheduled_at end
                                       when j.cancel_requested_at is not null then now()
                                       when p.retry_after_millis is not null
                                       then now() + p.retry_after_millis * interval '1 millisecond'
                                       else j.scheduled_at end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, last_error = p.error, updated_at = now()
              from (values (?::text, ?::integer, ?::bigint, ?::boolean))
                       as p (error, max_attempts, retry_after_millis, failure_handler),
                   %s
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensation_end_state is null
               and j.failure_end_state is null
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts, j.type"""
          .formatted(COMPENSATE_SQL);

  // The failure handler of a FAILING job ran (or was given up, or has no handler on this node): the
  // job ends in the state it was failing for.
  private static final String END_FAILING_SQL =
      """
            update bikesched_jobs
               set state = failure_end_state, failure_end_state = null, finished_at = now(),
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING' and failure_end_state is not null
            returning state, attempts, type, scheduled_at""";

  // The failure handler of a FAILING job failed: it runs again (after the delay, if any). The job
  // keeps its last_error.
  private static final String RETRY_FAILURE_HANDLER_SQL =
      """
            update bikesched_jobs j
               set state = 'FAILING',
                   scheduled_at = now() + coalesce(p.retry_after_millis, 0) * interval '1 millisecond',
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
              from (values (?::bigint)) as p (retry_after_millis)
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.failure_end_state is not null
            returning j.state, j.attempts, j.type, j.scheduled_at""";

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = case when failure_end_state is not null then 'FAILING'
                                when compensation_end_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
             where locked_by_node_id = ? and state = 'RUNNING'
            returning id, state, attempts, type""";

  // Releases a node's own jobs whose attempt has ended in memory without recording an outcome.
  // Recently claimed and still-active jobs are excluded, so an attempt cannot be released while it
  // may still write its outcome.
  private static final String RELEASE_INACTIVE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = case when failure_end_state is not null then 'FAILING'
                                when compensation_end_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
             where locked_by_node_id = ? and state = 'RUNNING'
               and locked_at < now() - make_interval(secs => ?)
               and id <> all(?::uuid[])
            returning id, state, attempts, type""";

  // Releases RUNNING jobs (back to SCHEDULED, or COMPENSATING) of nodes whose heartbeat is older
  // than the timeout (excluding the calling node); returns one row per released job with its dead
  // node. Concurrent callers on other nodes are safe: the second one blocks on the row locks and
  // then no longer matches state = 'RUNNING'.
  private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL =
      """
            update bikesched_jobs j
               set state = case when j.failure_end_state is not null then 'FAILING'
                                when j.compensation_end_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
              from bikesched_nodes n
             where j.locked_by_node_id = n.id
               and j.state = 'RUNNING'
               and n.id <> ?
               and n.last_heartbeat_at < now() - make_interval(secs => ?)
            returning n.id, n.name, j.id, j.state, j.attempts, j.type""";

  /**
   * Jobs released from a dead node.
   *
   * @param nodeId the dead node
   * @param nodeName its name
   * @param releasedJobs number of jobs put back to {@code SCHEDULED}
   */
  public record DeadNodeJobs(UUID nodeId, String nodeName, int releasedJobs) {}

  /** A row of {@code RELEASE_JOBS_OF_DEAD_NODES_SQL}. */
  private record ReleasedJob(
      UUID nodeId, String nodeName, UUID jobId, String state, int attempts) {}

  // The ORDER BY in CLAIM_JOBS_SQL only selects which jobs are claimed; UPDATE ... RETURNING
  // returns the claimed rows in no particular order, so they are sorted into claim order here.
  private static final Comparator<ClaimedJob> CLAIM_ORDER =
      Comparator.comparingInt(ClaimedJob::priority)
          .reversed()
          .thenComparing(ClaimedJob::scheduledAt);

  /**
   * Inserts a new {@code SCHEDULED} job; with an idempotency key, only unless a job of its type has
   * the key.
   *
   * @param jobId the job id
   * @param job the job's attributes
   * @return {@code jobId}, or the id of the existing job with the job's idempotency key
   */
  public UUID insertJob(UUID jobId, NewJob job) {
    return history.change(
        db -> insertJob(db, jobId, job),
        e ->
            new BikeschedException(
                "Scheduling bikesched job of type '" + job.type() + "' failed", e));
  }

  /**
   * Inserts a new scheduled job using the caller's connection, as part of the caller's transaction:
   * the connection is neither committed nor closed. See {@link #insertJob(UUID, NewJob)}.
   *
   * @param connection the caller's connection
   * @param jobId the new job's id
   * @param job the job's attributes
   * @return {@code jobId}, or the id of the existing job with the job's idempotency key
   */
  public UUID insertJob(Connection connection, UUID jobId, NewJob job) {
    return access.onCallerConnection(
        connection,
        db -> insertJob(db, jobId, job),
        e ->
            new BikeschedException(
                "Scheduling bikesched job of type '" + job.type() + "' failed", e));
  }

  private UUID insertJob(Sql db, UUID jobId, NewJob job) throws SQLException {
    if (job.idempotencyKey() == null) {
      db.update(INSERT_JOB_SQL, insertParameters(jobId, job));
      return inserted(db, jobId, job);
    }
    // A conflicting job deleted (retention) before it is found: insert again, once.
    Optional<UUID> existing = Optional.empty();
    for (int attempt = 1; attempt <= 2 && existing.isEmpty(); attempt++) {
      if (db.update(INSERT_KEYED_JOB_SQL, insertParameters(jobId, job)) == 1) {
        return inserted(db, jobId, job);
      }
      // A new statement: sees the conflicting job, also one committed while the insert waited.
      existing = db.one(FIND_KEYED_JOB_SQL, row -> row.uuid(1), job.type(), job.idempotencyKey());
    }
    return existing.orElseThrow(
        () ->
            new IllegalStateException(
                "The job with idempotency key '" + job.idempotencyKey() + "' disappeared twice"));
  }

  private UUID inserted(Sql db, UUID jobId, NewJob job) throws SQLException {
    db.due(job.type(), job.scheduledAt());
    history.record(db, created(jobId, job));
    return jobId;
  }

  /** The history entry of a new job (or occurrence). */
  private static Change created(UUID jobId, NewJob job) {
    String detail = "due at " + job.scheduledAt();
    if (job.recurrence() != null) {
      detail = "occurrence of '" + job.recurrence() + "', " + detail;
    }
    if (job.idempotencyKey() != null) {
      detail += ", idempotency key " + job.idempotencyKey();
    }
    return Change.job(jobId, "SCHEDULED", 0, null, detail);
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
      job.recurrence(),
      Sql.timestamp(job.plannedRunAt()),
      job.idempotencyKey()
    };
  }

  /**
   * Reads a job in a read-only transaction.
   *
   * @param jobId the job id
   * @return the job's id, type and state; empty if there is no such job
   */
  public Optional<DefaultJobInfo> findJob(UUID jobId) {
    return access.inReadOnlyTransaction(
        db ->
            db.one(
                FIND_JOB_SQL,
                row ->
                    new DefaultJobInfo(
                        jobId,
                        row.string(1),
                        JobState.valueOf(row.string(2)),
                        row.string(3),
                        row.uuid(4),
                        jsonMapper),
                jobId),
        e -> new BikeschedException("Reading bikesched job " + jobId + " failed", e));
  }

  /**
   * Claims up to {@code limit} due, scheduled jobs of the given types for a node using {@code FOR
   * UPDATE SKIP LOCKED} and marks them {@code RUNNING}.
   *
   * @param nodeId the claiming node
   * @param jobTypes the job types the node has handlers for
   * @param limit maximum number of jobs to claim
   * @return the claimed jobs, highest priority and earliest scheduled first
   */
  public List<ClaimedJob> claimJobs(UUID nodeId, Collection<String> jobTypes, int limit) {
    List<ClaimedJob> jobs =
        history.change(
            db -> {
              List<ClaimedJob> claimed =
                  db.list(
                      CLAIM_JOBS_SQL,
                      ClaimedJob::from,
                      nodeId,
                      Sql.array("text", jobTypes.toArray()),
                      limit);
              Set<UUID> polling = waitingAtPoll(db, claimed);
              history.record(
                  db,
                  claimed.stream()
                      .filter(job -> !polling.contains(job.id()))
                      .map(
                          job ->
                              Change.job(
                                  job.id(),
                                  JobState.RUNNING,
                                  job.attempts(),
                                  nodeId,
                                  claimDetail(job)))
                      .toList());
              return claimed;
            },
            e ->
                new BikeschedException(
                    "Claiming jobs for bikesched node " + nodeId + " failed", e));
    jobs.sort(CLAIM_ORDER);
    return jobs;
  }

  /**
   * The claimed jobs whose poll is waiting for its next check: their claims are not recorded in the
   * history (nor their suspension after the check, see {@code StepStore.suspendAtPoll}), so a poll
   * records its first wait and its outcome, not every check. Queried only with the history enabled.
   */
  private Set<UUID> waitingAtPoll(Sql db, List<ClaimedJob> claimed) throws SQLException {
    if (!history.isEnabled() || claimed.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(
        db.list(
            WAITING_AT_POLL_SQL,
            row -> row.uuid(1),
            Sql.array("uuid", claimed.stream().map(ClaimedJob::id).toArray())));
  }

  private static @Nullable String claimDetail(ClaimedJob job) {
    return switch (job.phase()) {
      case JobPhase.HandleFailure(JobState ignored, int run) -> "failure handler run " + run;
      case JobPhase.Compensate(JobState ignored, int attempt) -> "compensation attempt " + attempt;
      case JobPhase.Run ignored -> job.cancelRequested() ? "cancellation requested" : null;
    };
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED}.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @return {@code false} if the job is not running on this node (anymore)
   */
  public boolean completeJob(UUID jobId, JobLock lock) {
    return completeJob(ClaimEnd.of(jobId, lock), null).isPresent();
  }

  private boolean completeJob(Sql db, ClaimEnd end, @Nullable String result) throws SQLException {
    Optional<Integer> attempts =
        db.one(COMPLETE_JOB_SQL, row -> row.integer(1), result, end.jobId(), end.lock().token());
    if (attempts.isPresent()) {
      history.record(
          db,
          Change.job(
              end.jobId(),
              JobState.SUCCEEDED,
              attempts.get(),
              end.lock().nodeId(),
              result == null ? null : "succeeded with result"));
    }
    return attempts.isPresent();
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED} with its result and, in the same
   * transaction, runs its {@link FollowUp}.
   *
   * @param end the ending claim
   * @param result the job's result as JSON text, {@code null} if none
   * @return the job's new state; empty if the job is not running on this node (anymore)
   */
  public Optional<EndedJob> completeJob(ClaimEnd end, @Nullable String result) {
    if (end.followUp() instanceof FollowUp.None) {
      // One statement: no transaction unless the history is enabled.
      return history.change(
          db ->
              completeJob(db, end, result)
                  ? Optional.of(new EndedJob(JobState.SUCCEEDED, false))
                  : Optional.<EndedJob>empty(),
          e -> new BikeschedException("Completing bikesched job " + end.jobId() + " failed", e));
    }
    return access.inTransaction(
        db -> {
          if (!completeJob(db, end, result)) {
            return Optional.<EndedJob>empty();
          }
          return Optional.of(
              new EndedJob(JobState.SUCCEEDED, followUp(db, end, JobState.SUCCEEDED)));
        },
        e -> new BikeschedException("Completing bikesched job " + end.jobId() + " failed", e));
  }

  /**
   * Starts a child job as a step of its parent, exactly once: inserts the child and the parent's
   * {@code WAITING} step referencing it in one transaction, fenced by the parent's lock. If the
   * step exists, returns its child without scheduling; a {@code COMPENSATED} step (its parent was
   * retried after its compensation) starts a new child.
   *
   * @param parentJobId the parent
   * @param lock the lock of the parent's claim (node and token)
   * @param stepName the step starting the child
   * @param child the child's attributes
   * @param compensable whether the step has a compensation
   * @return the child's id, and whether it was started now
   * @throws IllegalStateException if the step name belongs to another type of step, or to a step
   *     that started a child of another type
   * @throws JobLockLostException if the parent is no longer running on the node
   */
  public ChildJob startChildJob(
      UUID parentJobId, JobLock lock, String stepName, NewJob child, boolean compensable) {
    return access.inTransaction(
        db -> {
          StepStore.requireJobLock(db, parentJobId, lock);
          Optional<ChildJobStep> step =
              db.one(
                  FIND_CHILD_JOB_STEP_SQL,
                  row ->
                      new ChildJobStep(
                          row.uuid(1),
                          row.enumValue(2, StepType.class),
                          row.enumValue(3, StepState.class),
                          row.uuid(4),
                          row.string(5)),
                  parentJobId,
                  stepName);
          if (step.isPresent()) {
            ChildJobStep found = step.get();
            StepType.CHILD_JOB.require(stepName, parentJobId, found.type());
            if (!found.childType().equals(child.type())) {
              throw new IllegalStateException(
                  "Step '"
                      + stepName
                      + "' of job "
                      + parentJobId
                      + " started job "
                      + found.childJobId()
                      + " of type "
                      + found.childType()
                      + ", not of type "
                      + child.type()
                      + "; step names must be unique within a job");
            }
            if (found.state() != StepState.COMPENSATED) {
              return new ChildJob(found.childJobId(), false);
            }
          }
          UUID childJobId = UuidUtil.timeOrdered();
          db.update(
              INSERT_CHILD_JOB_SQL,
              childJobId,
              child.type(),
              child.payload(),
              child.scheduledAt(),
              child.priority(),
              child.maxAttempts(),
              Sql.millis(child.timeout()),
              parentJobId);
          db.due(child.type(), child.scheduledAt());
          if (step.isPresent()) {
            db.update(RESTART_CHILD_JOB_STEP_SQL, childJobId, compensable, step.get().id());
          } else {
            db.update(
                INSERT_CHILD_JOB_STEP_SQL,
                UuidUtil.timeOrdered(),
                parentJobId,
                stepName,
                childJobId,
                child.type(),
                compensable);
          }
          history.record(
              db,
              List.of(
                  Change.job(
                      childJobId,
                      "SCHEDULED",
                      0,
                      null,
                      "started by job "
                          + parentJobId
                          + " step '"
                          + stepName
                          + "', due at "
                          + child.scheduledAt()),
                  Change.step(
                      parentJobId,
                      stepName,
                      "WAITING",
                      lock.nodeId(),
                      "started job " + childJobId + (step.isPresent() ? " again" : ""))));
          return new ChildJob(childJobId, true);
        },
        e ->
            new BikeschedException(
                "Starting a child job of type '"
                    + child.type()
                    + "' in step '"
                    + stepName
                    + "' of bikesched job "
                    + parentJobId
                    + " failed",
                e));
  }

  /**
   * A child job started by {@link #startChildJob}.
   *
   * @param id the child's id
   * @param started whether it was started now ({@code false}: by an earlier run of its parent)
   */
  public record ChildJob(UUID id, boolean started) {}

  /** A row of {@code FIND_CHILD_JOB_STEP_SQL}; {@code childType} is null for other types. */
  private record ChildJobStep(
      UUID id, StepType type, StepState state, UUID childJobId, String childType) {}

  /**
   * After a job's state changed in the caller's transaction: once it ended, runs the claim's {@link
   * FollowUp}.
   *
   * @param state the job's new state; nothing happens unless it is terminal
   * @return whether the next occurrence of a recurring job was inserted
   */
  private boolean followUp(Sql db, ClaimEnd end, JobState state) throws SQLException {
    if (!state.isEnded()) {
      return false;
    }
    return switch (end.followUp()) {
      case FollowUp.NotifyParent ignored -> {
        childEnded(db, end.jobId());
        yield false;
      }
      case FollowUp.ScheduleNext(var next) -> insertOccurrence(db, next);
      case FollowUp.None ignored -> false;
    };
  }

  /**
   * After a child job ended in a terminal state in the caller's transaction: completes its parent's
   * step waiting for it with its outcome and resumes the parent if it waits, see {@code
   * COMPLETE_CHILD_JOB_STEP_SQL}.
   *
   * @param jobId the job that ended
   */
  private void childEnded(Sql db, UUID jobId) throws SQLException {
    Optional<CompletedChildStep> completed =
        db.one(
            COMPLETE_CHILD_JOB_STEP_SQL,
            row -> new CompletedChildStep(row.uuid(1), row.string(2), row.string(3)),
            jobId);
    if (completed.isEmpty()) {
      return; // the step was cancelled or timed out, or the parent was deleted
    }
    UUID parent = completed.get().parentJobId();
    List<Change> changes = new ArrayList<>();
    changes.add(
        Change.step(
            parent,
            completed.get().stepName(),
            "SUCCEEDED",
            null,
            "job " + jobId + " ended " + completed.get().childState()));
    Optional<ChangedJob> resumed = db.one(RESUME_PARENT_SQL, ChangedJob::from, parent);
    if (resumed.isPresent()) {
      db.dueNow(resumed.get().type());
      changes.add(
          Change.job(
              parent, "SCHEDULED", resumed.get().attempts(), null, "resumed by job " + jobId));
    }
    history.record(db, changes);
  }

  /** A row of {@code COMPLETE_CHILD_JOB_STEP_SQL}. */
  private record CompletedChildStep(UUID parentJobId, String stepName, String childState) {}

  /**
   * Marks a job claimed by the node as {@code CANCELLED}; it is not retried.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param message why the job was cancelled, stored as {@code last_error}; may be {@code null}
   * @return {@code false} if the job is not running on this node (anymore)
   */
  public boolean cancelJob(UUID jobId, JobLock lock, @Nullable String message) {
    return cancelJob(ClaimEnd.of(jobId, lock), message, true).isPresent();
  }

  /**
   * Cancels a job claimed by the node: marks it {@code CANCELLED}, or {@code COMPENSATING} if it
   * has completed steps to compensate, and its unfinished steps {@code CANCELLED}. If the job ends
   * {@code CANCELLED}, its {@link FollowUp} runs in the same transaction (cancelling an occurrence
   * cancels this occurrence only).
   *
   * @param end the ending claim
   * @param message why the job was cancelled, may be {@code null}
   * @param attemptRan whether the handler ran in the claimed attempt; if not (cancelled on request
   *     right after claiming it), the attempt is not counted
   * @return the job's new state; empty if the job is not running on this node (anymore)
   */
  public Optional<EndedJob> cancelJob(ClaimEnd end, @Nullable String message, boolean attemptRan) {
    UUID jobId = end.jobId();
    JobLock lock = end.lock();
    return access.inTransaction(
        db -> {
          List<String> cancelledSteps =
              db.list(CANCEL_STEPS_SQL, row -> row.string(1), jobId, jobId, lock.token());
          history.record(
              db,
              cancelledSteps.stream()
                  .map(step -> Change.step(jobId, step, "CANCELLED", lock.nodeId(), null))
                  .toList());
          Optional<ChangedJob> cancelled =
              db.one(
                  CANCEL_JOB_SQL,
                  ChangedJob::from,
                  attemptRan ? 0 : 1,
                  ValidationUtil.truncateMessage(message),
                  jobId,
                  jobId,
                  lock.token());
          if (cancelled.isEmpty()) {
            return Optional.<EndedJob>empty();
          }
          JobState state = cancelled.get().state();
          if (state == JobState.COMPENSATING) {
            db.dueAt(cancelled.get().type(), cancelled.get().scheduledAt());
          }
          history.record(
              db, Change.job(jobId, state, cancelled.get().attempts(), lock.nodeId(), message));
          return Optional.of(new EndedJob(state, followUp(db, end, state)));
        },
        e -> new BikeschedException("Cancelling bikesched job " + jobId + " failed", e));
  }

  /**
   * A job after its attempt ended it, or started compensating it.
   *
   * @param state its new state, e.g. {@code CANCELLED} or {@code COMPENSATING}
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  public record EndedJob(JobState state, boolean nextOccurrenceScheduled) {}

  /**
   * Requests the cancellation of a job: the node claiming it next cancels it instead of running its
   * handler (compensating its steps, if any). Scheduled and waiting jobs are due right away; a
   * running job is cancelled after its current attempt unless that attempt ends it.
   *
   * @param jobId the job id
   * @param message why the job is cancelled, may be {@code null}
   * @return {@code false} if the job does not exist, has ended or is compensating
   */
  public boolean requestCancel(UUID jobId, @Nullable String message) {
    return history.change(
        db -> {
          Optional<ChangedJob> job =
              db.one(
                  REQUEST_CANCEL_SQL,
                  ChangedJob::from,
                  ValidationUtil.truncateMessage(message),
                  jobId);
          if (job.isPresent()) {
            if (job.get().state() != JobState.RUNNING) {
              db.dueNow(job.get().type()); // to be cancelled by the next claim
            }
            history.record(
                db,
                Change.job(
                    jobId,
                    job.get().state(),
                    job.get().attempts(),
                    null,
                    "cancellation requested" + (message == null ? "" : ": " + message)));
          }
          return job.isPresent();
        },
        e -> new BikeschedException("Cancelling bikesched job " + jobId + " failed", e));
  }

  /**
   * Schedules a {@code FAILED} job to run again right away, see {@code RETRY_JOB_SQL}.
   *
   * @param jobId the job
   * @return whether the job was failed and is now scheduled
   * @throws IllegalArgumentException if the job is an occurrence of a recurring job
   */
  public boolean retryJob(UUID jobId) {
    return history.change(
        db -> {
          Optional<ChangedJob> job = db.one(RETRY_JOB_SQL, ChangedJob::from, jobId);
          if (job.isPresent()) {
            db.dueNow(job.get().type());
            history.record(
                db, Change.job(jobId, job.get().state(), job.get().attempts(), null, "retried"));
            return true;
          }
          Optional<NotRetryable> notRetryable =
              db.one(
                  FIND_NOT_RETRYABLE_SQL,
                  row -> new NotRetryable(row.string(1), row.uuid(2)),
                  jobId);
          if (notRetryable.isPresent() && notRetryable.get().parentJobId() != null) {
            throw new IllegalArgumentException(
                "Job "
                    + jobId
                    + " is a child of job "
                    + notRetryable.get().parentJobId()
                    + " and cannot be retried: its parent may have its outcome already; the parent"
                    + " can start a new child under a new step name");
          }
          if (notRetryable.isPresent()) {
            throw new IllegalArgumentException(
                "Job "
                    + jobId
                    + " is an occurrence of recurring job "
                    + notRetryable.get().type()
                    + " and cannot be retried; its next occurrence runs as scheduled");
          }
          return false;
        },
        e -> new BikeschedException("Retrying bikesched job " + jobId + " failed", e));
  }

  /** A row of {@code FIND_NOT_RETRYABLE_SQL}: an occurrence, or a child job. */
  private record NotRetryable(String type, UUID parentJobId) {}

  /** The state, attempt count, type and due time of a job after a change. */
  private record ChangedJob(JobState state, int attempts, String type, Instant scheduledAt) {

    static ChangedJob from(Sql.Row row) throws SQLException {
      return new ChangedJob(
          row.enumValue(1, JobState.class), row.integer(2), row.string(3), row.instant(4));
    }
  }

  /**
   * Ends a compensating job claimed by the node, once all its compensations ran, in the state it
   * was compensated for ({@code CANCELLED} or {@code FAILED}; {@code FAILING} first for a failed
   * job with a failure handler); runs its {@link FollowUp} in the same transaction once it ended.
   *
   * @param end the ending claim
   * @return the job's new state; empty if the job is not compensating on this node (anymore)
   */
  public Optional<EndedJob> completeCompensation(ClaimEnd end) {
    UUID jobId = end.jobId();
    return access.inTransaction(
        db -> {
          Optional<ChangedJob> ended =
              db.one(
                  COMPLETE_COMPENSATION_SQL,
                  ChangedJob::from,
                  end.failureHandler(),
                  jobId,
                  end.lock().token());
          if (ended.isEmpty()) {
            return Optional.<EndedJob>empty();
          }
          JobState state = ended.get().state();
          if (state == JobState.FAILING) {
            db.dueAt(ended.get().type(), ended.get().scheduledAt());
          }
          history.record(
              db,
              Change.job(jobId, state, ended.get().attempts(), end.lock().nodeId(), "compensated"));
          return Optional.of(new EndedJob(state, followUp(db, end, state)));
        },
        e ->
            new BikeschedException(
                "Completing the compensation of bikesched job " + jobId + " failed", e));
  }

  /**
   * Records a failed compensation attempt of a job claimed by the node: compensating again while
   * compensation attempts remain, otherwise {@code COMPENSATION_FAILED} (then running its {@link
   * FollowUp} in the same transaction).
   *
   * @param end the ending claim; with a failure handler, the job is {@code FAILING} instead of
   *     {@code COMPENSATION_FAILED}
   * @param error description of the failure, stored as {@code last_error} once final
   * @param retryAfter delay of the next compensation attempt, {@code null} for as soon as possible
   * @return the job after recording the failure; empty if it is not compensating on this node
   */
  public Optional<FailedJob> failCompensation(
      ClaimEnd end, String error, @Nullable Duration retryAfter) {
    UUID jobId = end.jobId();
    return access.inTransaction(
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_COMPENSATION_SQL,
                  FailedJob::from,
                  ValidationUtil.truncateMessage(error),
                  Sql.millis(retryAfter),
                  end.failureHandler(),
                  jobId,
                  end.lock().token());
          if (failed.isEmpty()) {
            return failed;
          }
          FailedJob job = failed.get();
          job.reportDue(db);
          history.record(
              db,
              Change.job(
                  jobId,
                  job.state(),
                  job.attempts(),
                  end.lock().nodeId(),
                  "compensation failed: " + error));
          return Optional.of(job.withNextOccurrence(followUp(db, end, job.state())));
        },
        e ->
            new BikeschedException(
                "Recording the failed compensation of bikesched job " + jobId + " failed", e));
  }

  /**
   * Inserts an occurrence of a recurring job unless one is pending already.
   *
   * @param occurrence the occurrence
   * @return whether it was inserted
   */
  public boolean insertOccurrence(NewJob occurrence) {
    return access.inTransaction(
        db -> insertOccurrence(db, occurrence),
        e ->
            new BikeschedException(
                "Scheduling an occurrence of recurring job " + occurrence.type() + " failed", e));
  }

  private boolean insertOccurrence(Sql db, NewJob occurrence) throws SQLException {
    UUID jobId = UuidUtil.timeOrdered();
    if (db.update(INSERT_OCCURRENCE_SQL, insertParameters(jobId, occurrence)) == 0) {
      return false;
    }
    db.due(occurrence.type(), occurrence.scheduledAt());
    history.record(db, created(jobId, occurrence));
    return true;
  }

  /**
   * Registers a recurring job: inserts its first occurrence unless one is pending; a pending
   * occurrence of a different recurrence (description) that has not started yet is moved to the
   * first run of the new recurrence.
   *
   * @param first the first occurrence of the (new) recurrence
   * @return the pending occurrence after registering
   */
  public RecurringRegistration registerRecurringJob(NewJob first) {
    return access.inTransaction(
        db -> {
          boolean created = insertOccurrence(db, first);
          Optional<Moved> moved =
              created
                  ? Optional.empty()
                  : db.one(
                      RESCHEDULE_OCCURRENCE_SQL,
                      row -> new Moved(row.string(1), row.uuid(2), row.integer(3)),
                      first.recurrence(),
                      first.scheduledAt(),
                      Sql.timestamp(first.plannedRunAt()),
                      first.type(),
                      first.recurrence());
          if (moved.isPresent()) {
            db.dueAt(first.type(), first.scheduledAt());
            history.record(
                db,
                Change.job(
                    moved.get().jobId(),
                    "SCHEDULED",
                    moved.get().attempts(),
                    null,
                    "moved from recurrence '"
                        + moved.get().previousRecurrence()
                        + "' to '"
                        + first.recurrence()
                        + "', due at "
                        + first.scheduledAt()));
          }
          String previousRecurrence = moved.map(Moved::previousRecurrence).orElse(null);
          return db.one(
                  FIND_PENDING_OCCURRENCE_SQL,
                  row ->
                      new RecurringRegistration(
                          row.uuid(1), row.instant(2), row.string(3), created, previousRecurrence),
                  first.type())
              // Cannot happen within this transaction: the insert either succeeded or conflicted.
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No pending occurrence of recurring job " + first.type()));
        },
        e -> new BikeschedException("Registering recurring job " + first.type() + " failed", e));
  }

  /**
   * The pending occurrence of a recurring job after registering it.
   *
   * @param jobId the occurrence's job id
   * @param scheduledAt when it runs (or ran, if already running)
   * @param state its state
   * @param created whether registering created it
   * @param previousRecurrence the description of the recurrence it was moved away from, {@code
   *     null} if it was not moved
   */
  public record RecurringRegistration(
      UUID jobId,
      Instant scheduledAt,
      String state,
      boolean created,
      @Nullable String previousRecurrence) {}

  /** A pending occurrence moved to a changed recurrence. */
  private record Moved(String previousRecurrence, UUID jobId, int attempts) {}

  /**
   * Deletes the pending occurrences of a recurring job that are not running.
   *
   * @param type the job type of the recurring job
   * @return the number of deleted occurrences
   */
  public int deletePendingOccurrences(String type) {
    return history.change(
        db -> {
          List<Change> deleted =
              db.list(
                  DELETE_PENDING_OCCURRENCES_SQL,
                  row ->
                      Change.job(
                          row.uuid(1),
                          row.string(2),
                          row.integer(3),
                          null,
                          "deleted: recurring job removed"),
                  type);
          history.record(db, deleted);
          return deleted.size();
        },
        e -> new BikeschedException("Removing recurring job " + type + " failed", e));
  }

  /**
   * Records a failed attempt of a job claimed by the node: the job is scheduled again while
   * attempts remain, otherwise it is marked {@code FAILED}.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param error description of the failure, stored as {@code last_error}
   * @param maxAttempts the job's new maximum number of attempts, {@code null} to keep it
   * @param retryAfter delay of the next attempt from now, {@code null} to retry as soon as possible
   * @return the job after recording the failure; empty if the job is not running on this node
   *     (anymore)
   */
  public Optional<FailedJob> failJob(
      UUID jobId,
      JobLock lock,
      String error,
      @Nullable Integer maxAttempts,
      @Nullable Duration retryAfter) {
    return failJob(ClaimEnd.of(jobId, lock), error, maxAttempts, retryAfter);
  }

  /**
   * Records a failed attempt like {@link #failJob(UUID, JobLock, String, Integer, Duration)}; if
   * the job fails permanently, its {@link FollowUp} runs in the same transaction.
   *
   * @param end the ending claim; with a failure handler, the job is {@code FAILING} instead of
   *     {@code FAILED}
   * @param error description of the failure, stored as {@code last_error}
   * @param maxAttempts the job's new maximum number of attempts, {@code null} to keep it
   * @param retryAfter delay of the next attempt from now, {@code null} to retry as soon as possible
   * @return the job after recording the failure; empty if the job is not running on this node
   *     (anymore)
   */
  public Optional<FailedJob> failJob(
      ClaimEnd end, String error, @Nullable Integer maxAttempts, @Nullable Duration retryAfter) {
    UUID jobId = end.jobId();
    return access.inTransaction(
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_JOB_SQL,
                  FailedJob::from,
                  ValidationUtil.truncateMessage(error),
                  Sql.typed(maxAttempts, Types.INTEGER),
                  Sql.millis(retryAfter),
                  end.failureHandler(),
                  jobId,
                  jobId,
                  end.lock().token());
          if (failed.isEmpty()) {
            return failed;
          }
          FailedJob job = failed.get();
          job.reportDue(db);
          history.record(
              db,
              Change.job(
                  jobId,
                  job.state(),
                  job.attempts(),
                  end.lock().nodeId(),
                  job.state() == JobState.SCHEDULED
                      ? error + " (retry at " + job.scheduledAt() + ")"
                      : error));
          return Optional.of(job.withNextOccurrence(followUp(db, end, job.state())));
        },
        e -> new BikeschedException("Recording failure of bikesched job " + jobId + " failed", e));
  }

  /**
   * Ends a {@code FAILING} job claimed by the node in the state it was failing for ({@code FAILED}
   * or {@code COMPENSATION_FAILED}), once its failure handler ran; runs its {@link FollowUp} in the
   * same transaction.
   *
   * @param end the ending claim
   * @param detail recorded in the job history
   * @return the job's new state; empty if the job is not failing on this node (anymore)
   */
  public Optional<EndedJob> endFailing(ClaimEnd end, @Nullable String detail) {
    UUID jobId = end.jobId();
    return access.inTransaction(
        db -> {
          Optional<ChangedJob> ended =
              db.one(END_FAILING_SQL, ChangedJob::from, jobId, end.lock().token());
          if (ended.isEmpty()) {
            return Optional.<EndedJob>empty();
          }
          JobState state = ended.get().state();
          history.record(
              db, Change.job(jobId, state, ended.get().attempts(), end.lock().nodeId(), detail));
          return Optional.of(new EndedJob(state, followUp(db, end, state)));
        },
        e ->
            new BikeschedException(
                "Recording the end of failing bikesched job " + jobId + " failed", e));
  }

  /**
   * Records a failed run of the failure handler of a {@code FAILING} job claimed by the node: it
   * runs again after the delay.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param error description of the handler's failure, recorded in the job history only
   * @param retryAfter delay of the next run, {@code null} for as soon as possible
   * @return when the handler runs again; empty if the job is not failing on this node (anymore)
   */
  public Optional<Instant> retryFailureHandler(
      UUID jobId, JobLock lock, String error, @Nullable Duration retryAfter) {
    return history.change(
        db -> {
          Optional<ChangedJob> failing =
              db.one(
                  RETRY_FAILURE_HANDLER_SQL,
                  ChangedJob::from,
                  Sql.millis(retryAfter),
                  jobId,
                  lock.token());
          if (failing.isEmpty()) {
            return Optional.<Instant>empty();
          }
          db.dueAt(failing.get().type(), failing.get().scheduledAt());
          history.record(
              db,
              Change.job(
                  jobId,
                  JobState.FAILING,
                  failing.get().attempts(),
                  lock.nodeId(),
                  "failure handler failed: "
                      + error
                      + " (runs again at "
                      + failing.get().scheduledAt()
                      + ")"));
          return Optional.of(failing.get().scheduledAt());
        },
        e ->
            new BikeschedException(
                "Recording the failed failure handler of bikesched job " + jobId + " failed", e));
  }

  /**
   * A job after recording a failed attempt or compensation attempt.
   *
   * @param state {@code SCHEDULED} (retried), {@code COMPENSATING} (compensating now, or again),
   *     {@code FAILING} (its failure handler runs next), {@code FAILED} or {@code
   *     COMPENSATION_FAILED}
   * @param maxAttempts its maximum number of (compensation) attempts
   * @param scheduledAt when it runs next (only meaningful unless it ended)
   * @param attempts its number of attempts
   * @param type its job type
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  public record FailedJob(
      JobState state,
      int maxAttempts,
      Instant scheduledAt,
      int attempts,
      String type,
      boolean nextOccurrenceScheduled) {

    /** Maps a row of {@code FAIL_JOB_SQL} or {@code FAIL_COMPENSATION_SQL}. */
    static FailedJob from(Sql.Row row) throws SQLException {
      return new FailedJob(
          row.enumValue(1, JobState.class),
          row.integer(2),
          row.instant(3),
          row.integer(4),
          row.string(5),
          false);
    }

    FailedJob withNextOccurrence(boolean scheduled) {
      return new FailedJob(state, maxAttempts, scheduledAt, attempts, type, scheduled);
    }

    /**
     * A retried, compensating or failing job is due again (right away or after its delay): the
     * failing node wakes itself up for it, other nodes are not told.
     */
    void reportDue(Sql db) {
      if (!state.isEnded()) {
        db.dueAt(type, scheduledAt);
      }
    }
  }

  /**
   * Puts all jobs still running on the node back to {@code SCHEDULED}, e.g. on shutdown.
   *
   * @param nodeId the node id
   * @return the number of released jobs
   */
  public int releaseJobs(UUID nodeId) {
    return history.change(
        db -> {
          List<Change> released =
              db.list(
                  RELEASE_JOBS_SQL,
                  row -> {
                    db.dueNow(row.string(4));
                    return Change.job(
                        row.uuid(1),
                        row.string(2),
                        row.integer(3),
                        nodeId,
                        "released by its node (stopped or restarted)");
                  },
                  nodeId);
          history.record(db, released);
          return released.size();
        },
        e -> new BikeschedException("Releasing jobs of bikesched node " + nodeId + " failed", e));
  }

  /**
   * Releases this node's stale, no-longer-active jobs after an outcome could not be recorded.
   *
   * @param nodeId the node owning the jobs
   * @param grace the minimum age of a claim that may be recovered
   * @param activeJobIds jobs with attempts still running in memory
   * @return the number of released jobs
   */
  public int releaseInactiveJobs(UUID nodeId, Duration grace, Collection<UUID> activeJobIds) {
    return history.change(
        db -> {
          List<Change> released =
              db.list(
                  RELEASE_INACTIVE_JOBS_SQL,
                  row -> {
                    db.dueNow(row.string(4));
                    return Change.job(
                        row.uuid(1),
                        row.string(2),
                        row.integer(3),
                        nodeId,
                        "released after its outcome could not be recorded");
                  },
                  nodeId,
                  Sql.seconds(grace),
                  Sql.array("uuid", activeJobIds.toArray()));
          history.record(db, released);
          return released.size();
        },
        e -> new BikeschedException("Releasing inactive bikesched jobs failed", e));
  }

  /**
   * Puts the {@code RUNNING} jobs of dead nodes (no heartbeat within {@code nodeTimeout}) back to
   * {@code SCHEDULED}.
   *
   * @param callingNodeId the node doing the check; never treated as dead
   * @param nodeTimeout heartbeat age after which a node counts as dead
   * @return the dead nodes that had running jobs, with the number of released jobs
   */
  public List<DeadNodeJobs> releaseJobsOfDeadNodes(UUID callingNodeId, Duration nodeTimeout) {
    return history.change(
        db -> {
          List<ReleasedJob> released =
              db.list(
                  RELEASE_JOBS_OF_DEAD_NODES_SQL,
                  row -> {
                    db.dueNow(row.string(6));
                    return new ReleasedJob(
                        row.uuid(1), row.string(2), row.uuid(3), row.string(4), row.integer(5));
                  },
                  callingNodeId,
                  Sql.seconds(nodeTimeout));
          List<Change> changes = new ArrayList<>();
          Map<UUID, DeadNodeJobs> perNode = new LinkedHashMap<>();
          for (ReleasedJob job : released) {
            changes.add(
                Change.job(
                    job.jobId(),
                    job.state(),
                    job.attempts(),
                    callingNodeId,
                    "released from dead node " + job.nodeName()));
            perNode.merge(
                job.nodeId(),
                new DeadNodeJobs(job.nodeId(), job.nodeName(), 1),
                (a, b) -> new DeadNodeJobs(a.nodeId(), a.nodeName(), a.releasedJobs() + 1));
          }
          history.record(db, changes);
          return List.copyOf(perNode.values());
        },
        e -> new BikeschedException("Releasing jobs of dead bikesched nodes failed", e));
  }

  /**
   * The channel on which nodes announce job types with jobs due now (the payload is the type), see
   * {@link DueJobs#dueNow}.
   */
  public static final String CHANNEL = "bikesched_jobs";

  // One notification per type; PostgreSQL also merges equal notifications of a transaction. Sent
  // by the notifier in an auto-commit statement of its own (a transaction issuing NOTIFY takes a
  // database-wide lock at commit, so job writes never notify), and inside a caller's transaction
  // for writes on the caller's connection (sent on commit).
  static final String NOTIFY_DUE_SQL =
      "select pg_notify('" + CHANNEL + "', t) from unnest(?::text[]) t";

  // How long until the earliest job of the types is due, within the window only: bounded, the
  // lookup reads the poll index entries of the window instead of all delayed jobs. The database
  // computes the delay, so the nodes' clocks don't matter.
  private static final String NEXT_DUE_SQL =
      """
            select ceil(extract(epoch from min(scheduled_at) - now()) * 1000)::bigint
              from bikesched_jobs
             where state in ('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING')
               and scheduled_at > now() and scheduled_at <= now() + ? * interval '1 millisecond'
               and type = any(?)""";

  /**
   * Notifies all listening nodes that jobs of these types are due now, in an auto-commit statement
   * of its own.
   *
   * @param types the job types (any text: nodes ignore types they don't handle)
   */
  public void notifyDue(Collection<String> types) {
    access.withConnection(
        db -> {
          db.execute(NOTIFY_DUE_SQL, Sql.array("text", types.toArray()));
          return null;
        },
        e -> new BikeschedException("Notifying due jobs failed", e));
  }

  /**
   * How long until the earliest job of the given types becomes due, if one does within the window
   * (jobs due already don't count).
   *
   * @param types the job types
   * @param window how far to look ahead
   * @return the delay, empty if no job of the types becomes due within the window
   */
  public Optional<Duration> nextDueIn(Collection<String> types, Duration window) {
    return access
        .withConnection(
            db ->
                db.one(
                    NEXT_DUE_SQL,
                    row -> Optional.ofNullable(row.millis(1)),
                    window.toMillis(),
                    Sql.array("text", types.toArray())),
            e -> new BikeschedException("Looking up the next due job failed", e))
        .flatMap(delay -> delay);
  }

  /**
   * Starts listening for the notifications of {@link #notifyDue} on a connection of its own, kept
   * until the returned listening is closed.
   *
   * @return the listening connection
   * @throws BikeschedException if connecting or listening fails
   * @throws UnsupportedOperationException if the data source's connections are no PostgreSQL JDBC
   *     connections (e.g. wrapped by a pool that cannot unwrap them)
   */
  public DueJobsListening listen() {
    return access.listen(
        CHANNEL, e -> new BikeschedException("Listening for due bikesched jobs failed", e));
  }

  // A batch of ended jobs past the retention, oldest first from the given finished_at on (see
  // DeletedBatch); their steps go with them (on delete cascade). Skips locked rows, so concurrent
  // clean-ups on several nodes delete different jobs.
  private static final String DELETE_FINISHED_JOBS_SQL =
      """
            with cutoff as (select coalesce(?, now() - make_interval(secs => ?)) as at),
            batch as (
                select id from bikesched_jobs
                 where finished_at >= coalesce(?, '-infinity'::timestamptz)
                   and finished_at < (select at from cutoff)
                   and state in ('SUCCEEDED', 'FAILED', 'CANCELLED')
                 order by finished_at
                 limit ? for update skip locked),
            deleted as (
                delete from bikesched_jobs j using batch b where j.id = b.id
                returning j.finished_at)
            select count(*), max(finished_at), (select at from cutoff) from deleted""";

  /**
   * Deletes up to {@code limit} ended jobs ({@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED})
   * that finished longer than {@code retention} ago, with their steps, oldest first. The job
   * history is kept, it has its own retention.
   *
   * @param after only jobs finished at or after this time, {@code null} for all
   * @param cutoff only jobs finished before this time, {@code null} for {@code retention} ago
   * @param retention how long ended jobs are kept
   * @param limit the maximum number of jobs to delete
   * @return the deleted batch
   */
  public DeletedBatch deleteFinishedJobs(
      @Nullable Instant after, @Nullable Instant cutoff, Duration retention, int limit) {
    return access.withConnection(
        db ->
            db.one(
                    DELETE_FINISHED_JOBS_SQL,
                    DeletedBatch.MAPPER,
                    Sql.timestamp(cutoff),
                    Sql.seconds(retention),
                    Sql.timestamp(after),
                    limit)
                .orElseThrow(),
        e -> new BikeschedException("Deleting finished bikesched jobs failed", e));
  }

  /**
   * A job claimed for execution, as stored; becomes a {@code DefaultJob} on the claiming node.
   *
   * @param id the job id
   * @param type the job type
   * @param payload the payload as JSON text, may be {@code null}
   * @param priority its priority
   * @param scheduledAt when it was due
   * @param attempts the number of attempts including the claimed one
   * @param maxAttempts the maximum number of attempts
   * @param timeout its timeout, {@code null} for the node default
   * @param phase what it was claimed for: running its handler, compensating or its failure handler
   * @param origin one-off, a child job or an occurrence of a recurring job
   * @param cancelRequested whether its cancellation was requested via {@code Bikesched.cancel}
   * @param cancelMessage the message of that request, may be {@code null}
   * @param lock the claiming node and the token of the claim; the attempt's writes are fenced by it
   * @param lastError the stored description of its last failure, may be {@code null}
   */
  public record ClaimedJob(
      UUID id,
      String type,
      @Nullable String payload,
      int priority,
      Instant scheduledAt,
      int attempts,
      int maxAttempts,
      @Nullable Duration timeout,
      JobPhase phase,
      JobOrigin origin,
      boolean cancelRequested,
      @Nullable String cancelMessage,
      JobLock lock,
      @Nullable String lastError) {

    /** Maps a row of {@code CLAIM_JOBS_SQL}. */
    static ClaimedJob from(Sql.Row row) throws SQLException {
      int attempts = row.integer(6);
      JobState compensatedState = row.enumValue(11, JobState.class);
      JobState failingState = row.enumValue(18, JobState.class);
      JobPhase phase;
      if (failingState != null) {
        phase = new JobPhase.HandleFailure(failingState, row.integer(19));
      } else if (compensatedState != null) {
        phase = new JobPhase.Compensate(compensatedState, row.integer(12));
      } else {
        phase = new JobPhase.Run(attempts);
      }
      return new ClaimedJob(
          row.uuid(1),
          row.string(2),
          row.string(3),
          row.integer(4),
          row.instant(5),
          attempts,
          row.integer(7),
          row.millis(8),
          phase,
          JobOrigin.of(row.uuid(17), row.string(9), row.instant(10)),
          row.bool(13),
          row.string(14),
          new JobLock(row.uuid(15), row.uuid(16)),
          row.string(20));
    }
  }
}
