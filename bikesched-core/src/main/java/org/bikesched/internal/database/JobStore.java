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
import org.bikesched.history.Actor;
import org.bikesched.history.ActorType;
import org.bikesched.history.JobAction;
import org.bikesched.history.StepAction;
import org.bikesched.internal.database.ClaimEnd.FollowUp;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.bikesched.job.StepState;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_jobs} table: inserting, claiming and finishing jobs, releasing the jobs of
 * stopped or dead nodes, the occurrences of recurring jobs, and child jobs (started as steps of
 * their parent, whose step receives their outcome when they end). Changes are recorded in the
 * {@link HistoryStore history}, if enabled. Thread-safe; every call borrows its own connection from
 * the {@link DatabaseAccess}.
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
            insert into bikesched_jobs (id, name, type, payload, scheduled_at, priority, max_attempts, timeout_millis, recurrence, planned_run_at, idempotency_key, reference)
            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)""";

  // Inserts a job with an idempotency key unless a job of its name has the key (partial unique
  // index); FIND_KEYED_JOB_SQL then finds that job.
  private static final String INSERT_KEYED_JOB_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (name, idempotency_key) where idempotency_key is not null do nothing""";

  private static final String FIND_KEYED_JOB_SQL =
      "select id from bikesched_jobs where name = ? and idempotency_key = ?";

  private static final String INSERT_CHILD_JOB_SQL =
      """
            insert into bikesched_jobs (id, name, type, payload, scheduled_at, priority, max_attempts, timeout_millis, parent_job_id, reference)
            values (?, ?, 'CHILD', ?::jsonb, ?, ?, ?, ?, ?, ?)""";

  // The step of a job starting a child job.
  private static final String FIND_CHILD_JOB_STEP_SQL =
      """
            select id, type, state, child_job_id, child_job_name
              from bikesched_steps
             where job_id = ? and name = ?
               for update""";

  private static final String INSERT_CHILD_JOB_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, child_job_id, child_job_name, compensable)
            values (?, ?, ?, 'CHILD_JOB', 'WAITING', 1, ?, ?, ?)""";

  // A compensated child job step runs again when its job is retried: it starts a new child of the
  // same name.
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
            returning s.job_id, s.id, c.state as child_state""";

  // A WAITING parent is due again (it may wait for another step: it replays and suspends again); a
  // RUNNING one is left alone, its wait or suspension finds the step completed.
  private static final String RESUME_PARENT_SQL =
      """
            update bikesched_jobs set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
             where id = ? and state = 'WAITING'
            returning state, attempts, name, scheduled_at""";

  // Inserts an occurrence of a recurring job unless one is pending already (partial unique index).
  private static final String INSERT_OCCURRENCE_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (name) where type = 'RECURRING' and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')
            do nothing""";

  // Moves a pending (not yet running) occurrence to a new recurrence; returns the old description.
  private static final String RESCHEDULE_OCCURRENCE_SQL =
      """
            update bikesched_jobs j
               set recurrence = ?, scheduled_at = ?, planned_run_at = ?, updated_at = now()
              from (select id, recurrence from bikesched_jobs
                     where name = ? and type = 'RECURRING' and state = 'SCHEDULED'
                       and recurrence <> ?
                     for update) old
             where j.id = old.id
            returning old.recurrence, j.id, j.attempts""";

  private static final String FIND_PENDING_OCCURRENCE_SQL =
      """
            select id, scheduled_at, state from bikesched_jobs
             where name = ? and type = 'RECURRING' and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')""";

  // Running occurrences cannot be deleted; finishing them schedules no successor on nodes that no
  // longer have the recurring job registered.
  private static final String DELETE_PENDING_OCCURRENCES_SQL =
      """
            delete from bikesched_jobs
             where name = ? and type = 'RECURRING' and state in ('SCHEDULED', 'WAITING')
            returning id, state, attempts, name""";

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
  // a FAILING job a run of its failure handler (failure_end_state stays set). Only jobs the node
  // has handlers for: during a rolling deploy, nodes of the old version leave jobs of new names to
  // the new ones (the name is a key column of the polling index, so the filter is checked on index
  // entries, in claim order, without visiting other rows).
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
                             and name = any(?)
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, name, payload::text as payload, priority, scheduled_at, attempts, max_attempts,
                      timeout_millis, recurrence, planned_run_at, compensation_end_state,
                      compensation_attempts, cancel_requested_at is not null as cancel_requested,
                      cancel_message,
                      locked_by_node_id, lock_token, parent_job_id, failure_end_state,
                      failure_attempts, last_error, reference""";

  private static final String FIND_JOB_SQL =
      """
            select name, type, state, result::text as result, parent_job_id, reference from bikesched_jobs where id = ?""";

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
            returning j.state, j.attempts, j.name, j.scheduled_at"""
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
            returning id""";

  // Bikesched.retry(jobId): a FAILED job runs again with all its attempts, keeping its completed
  // steps (compensated ones are COMPENSATED and run again). Not for occurrences of recurring jobs
  // and child jobs (FIND_NOT_RETRYABLE_SQL tells them apart when nothing was retried).
  private static final String RETRY_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED', attempts = 0, compensation_end_state = null, compensation_attempts = 0,
                   failure_attempts = 0, cancel_requested_at = null, cancel_message = null,
                   scheduled_at = now(), finished_at = null, updated_at = now()
             where id = ? and state = 'FAILED' and type = 'SINGLE'
            returning state, attempts, name, scheduled_at""";

  private static final String FIND_NOT_RETRYABLE_SQL =
      """
            select name, type, parent_job_id from bikesched_jobs
             where id = ? and type <> 'SINGLE'""";

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
            returning state, attempts, name, scheduled_at""";

  // All compensations of a compensating job ran: it ends in the state it was compensated for, or
  // is FAILING first if it failed and it has a failure handler on this node (p.*).
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
            returning j.state, j.attempts, j.name, j.scheduled_at""";

  // A failed compensation attempt: compensating again (after the delay, if any) while compensation
  // attempts remain (max_attempts applies to them as well), otherwise COMPENSATION_FAILED, or
  // FAILING first if it has a failure handler on this node.
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
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts, j.name""";

  // A failed attempt: retried (back to SCHEDULED) while attempts remain, otherwise FAILED, or
  // COMPENSATING first if it has steps to compensate, or FAILING first if it has a failure handler
  // on this node. The handler may set a new maximum number of
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
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts, j.name"""
          .formatted(COMPENSATE_SQL);

  // The failure handler of a FAILING job ran (or was given up, or has no handler on this node): the
  // job ends in the state it was failing for.
  private static final String END_FAILING_SQL =
      """
            update bikesched_jobs
               set state = failure_end_state, failure_end_state = null, finished_at = now(),
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING' and failure_end_state is not null
            returning state, attempts, name, scheduled_at""";

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
            returning j.state, j.attempts, j.name, j.scheduled_at""";

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = case when failure_end_state is not null then 'FAILING'
                                when compensation_end_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by_node_id = null, lock_token = null, locked_at = null, updated_at = now()
             where locked_by_node_id = ? and state = 'RUNNING'
            returning id, state, attempts, name""";

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
            returning id, state, attempts, name""";

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
            returning n.id as node_id, n.name as node_name, j.id as job_id, j.state, j.attempts,
                      j.name""";

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
      UUID nodeId, String nodeName, UUID jobId, JobState state, int attempts) {}

  // The ORDER BY in CLAIM_JOBS_SQL only selects which jobs are claimed; UPDATE ... RETURNING
  // returns the claimed rows in no particular order, so they are sorted into claim order here.
  private static final Comparator<ClaimedJob> CLAIM_ORDER =
      Comparator.comparingInt(ClaimedJob::priority)
          .reversed()
          .thenComparing(ClaimedJob::scheduledAt);

  /**
   * Inserts a new {@code SCHEDULED} job; with an idempotency key, only unless a job of its name has
   * the key.
   *
   * @param jobId the job id
   * @param job the job's attributes
   * @return {@code jobId}, or the id of the existing job with the job's idempotency key
   */
  public UUID insertJob(UUID jobId, NewJob job) {
    return history.change(
        db -> insertJob(db, jobId, job, ActorType.APPLICATION),
        e -> new BikeschedException("Scheduling bikesched job '" + job.name() + "' failed", e));
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
        db -> insertJob(db, jobId, job, ActorType.APPLICATION),
        e -> new BikeschedException("Scheduling bikesched job '" + job.name() + "' failed", e));
  }

  private UUID insertJob(Sql db, UUID jobId, NewJob job, ActorType actor) throws SQLException {
    if (job.idempotencyKey() == null) {
      db.update(INSERT_JOB_SQL, insertParameters(jobId, job));
      return inserted(db, jobId, job, actor);
    }
    // A conflicting job deleted (retention) before it is found: insert again, once.
    Optional<UUID> existing = Optional.empty();
    for (int attempt = 1; attempt <= 2 && existing.isEmpty(); attempt++) {
      if (db.update(INSERT_KEYED_JOB_SQL, insertParameters(jobId, job)) == 1) {
        return inserted(db, jobId, job, actor);
      }
      // A new statement: sees the conflicting job, also one committed while the insert waited.
      existing =
          db.one(FIND_KEYED_JOB_SQL, row -> row.uuid("id"), job.name(), job.idempotencyKey());
    }
    return existing.orElseThrow(
        () ->
            new IllegalStateException(
                "The job with idempotency key '" + job.idempotencyKey() + "' disappeared twice"));
  }

  private UUID inserted(Sql db, UUID jobId, NewJob job, ActorType actor) throws SQLException {
    db.due(job.name(), job.scheduledAt());
    history.record(db, created(jobId, job, actor));
    return jobId;
  }

  /** The history entry of a new job (or occurrence). */
  private static Change created(UUID jobId, NewJob job, ActorType actor) {
    return Change.job(jobId, JobAction.SCHEDULED, JobState.SCHEDULED, 0, actor)
        .actor(job.actor())
        .dueAt(job.scheduledAt())
        .recurrence(job.recurrence(), null)
        .keys(job.idempotencyKey(), job.reference());
  }

  private static Object[] insertParameters(UUID jobId, NewJob job) {
    return new Object[] {
      jobId,
      job.name(),
      (job.recurrence() == null ? JobType.SINGLE : JobType.RECURRING).name(),
      job.payload(),
      job.scheduledAt(),
      job.priority(),
      job.maxAttempts(),
      Sql.millis(job.timeout()),
      job.recurrence(),
      Sql.timestamp(job.plannedRunAt()),
      job.idempotencyKey(),
      job.reference()
    };
  }

  /**
   * Reads a job in a read-only transaction.
   *
   * @param jobId the job id
   * @return the job's id, name, type and state; empty if there is no such job
   */
  public Optional<DefaultJobInfo> findJob(UUID jobId) {
    return access.inReadOnlyTransaction(
        db ->
            db.one(
                FIND_JOB_SQL,
                row ->
                    new DefaultJobInfo(
                        jobId,
                        row.string("name"),
                        row.enumValue("type", JobType.class),
                        row.enumValue("state", JobState.class),
                        row.string("result"),
                        row.uuid("parent_job_id"),
                        row.string("reference"),
                        jsonMapper),
                jobId),
        e -> new BikeschedException("Reading bikesched job " + jobId + " failed", e));
  }

  /**
   * Claims up to {@code limit} due, scheduled jobs of the given names for a node using {@code FOR
   * UPDATE SKIP LOCKED} and marks them {@code RUNNING}.
   *
   * @param nodeId the claiming node
   * @param jobNames the job names the node has handlers for
   * @param limit maximum number of jobs to claim
   * @return the claimed jobs, highest priority and earliest scheduled first
   */
  public List<ClaimedJob> claimJobs(UUID nodeId, Collection<String> jobNames, int limit) {
    List<ClaimedJob> jobs =
        history.change(
            db -> {
              List<ClaimedJob> claimed =
                  db.list(
                      CLAIM_JOBS_SQL,
                      ClaimedJob::from,
                      nodeId,
                      Sql.array("text", jobNames.toArray()),
                      limit);
              Set<UUID> polling = waitingAtPoll(db, claimed);
              history.record(
                  db,
                  claimed.stream()
                      .filter(job -> !polling.contains(job.id()))
                      .map(JobStore::claimed)
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
            row -> row.uuid("job_id"),
            Sql.array("uuid", claimed.stream().map(ClaimedJob::id).toArray())));
  }

  /** The history entry of a claim: what the job was claimed for. */
  private static Change claimed(ClaimedJob job) {
    Change change =
        Change.job(job.id(), JobAction.CLAIMED, JobState.RUNNING, job.attempts(), ActorType.NODE);
    return switch (job.phase()) {
      case JobPhase.HandleFailure(JobState ignored, int run) ->
          change.reason("FAILURE_HANDLER").run(run);
      case JobPhase.Compensate(JobState ignored, int attempt) ->
          change.reason("COMPENSATION").run(attempt);
      case JobPhase.Run ignored -> change.reason(job.cancelRequested() ? "CANCELLATION" : null);
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
        db.one(
            COMPLETE_JOB_SQL,
            row -> row.integer("attempts"),
            result,
            end.jobId(),
            end.lock().token());
    if (attempts.isPresent()) {
      history.record(
          db,
          Change.job(
              end.jobId(),
              JobAction.SUCCEEDED,
              JobState.SUCCEEDED,
              attempts.get(),
              ActorType.NODE));
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
   *     that started a child of another name
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
                          row.uuid("id"),
                          row.enumValue("type", StepType.class),
                          row.enumValue("state", StepState.class),
                          row.uuid("child_job_id"),
                          row.string("child_job_name")),
                  parentJobId,
                  stepName);
          if (step.isPresent()) {
            ChildJobStep found = step.get();
            StepType.CHILD_JOB.require(stepName, parentJobId, found.type());
            if (!found.childName().equals(child.name())) {
              throw new IllegalStateException(
                  "Step '"
                      + stepName
                      + "' of job "
                      + parentJobId
                      + " started job "
                      + found.childJobId()
                      + " named "
                      + found.childName()
                      + ", not "
                      + child.name()
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
              child.name(),
              child.payload(),
              child.scheduledAt(),
              child.priority(),
              child.maxAttempts(),
              Sql.millis(child.timeout()),
              parentJobId,
              child.reference());
          db.due(child.name(), child.scheduledAt());
          UUID stepId = step.isPresent() ? step.get().id() : UuidUtil.timeOrdered();
          if (step.isPresent()) {
            db.update(RESTART_CHILD_JOB_STEP_SQL, childJobId, compensable, stepId);
          } else {
            db.update(
                INSERT_CHILD_JOB_STEP_SQL,
                stepId,
                parentJobId,
                stepName,
                childJobId,
                child.name(),
                compensable);
          }
          history.record(
              db,
              List.of(
                  Change.job(childJobId, JobAction.SCHEDULED, JobState.SCHEDULED, 0, ActorType.JOB)
                      .causedBy(parentJobId)
                      .dueAt(child.scheduledAt())
                      .keys(null, child.reference()),
                  Change.step(
                          parentJobId,
                          stepId,
                          StepAction.CHILD_STARTED,
                          StepState.WAITING,
                          ActorType.JOB)
                      .child(childJobId, null)));
          return new ChildJob(childJobId, true);
        },
        e ->
            new BikeschedException(
                "Starting child job '"
                    + child.name()
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

  /** A row of {@code FIND_CHILD_JOB_STEP_SQL}; {@code childName} is null for other types. */
  private record ChildJobStep(
      UUID id, StepType type, StepState state, UUID childJobId, String childName) {}

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
      case FollowUp.ScheduleNext(var next) -> insertOccurrence(db, next, ActorType.NODE);
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
            row ->
                new CompletedChildStep(
                    row.uuid("job_id"), row.uuid("id"), row.string("child_state")),
            jobId);
    if (completed.isEmpty()) {
      return; // the step was cancelled or timed out, or the parent was deleted
    }
    UUID parent = completed.get().parentJobId();
    List<Change> changes = new ArrayList<>();
    changes.add(
        Change.step(
                parent,
                completed.get().stepId(),
                StepAction.CHILD_ENDED,
                StepState.SUCCEEDED,
                ActorType.NODE)
            .causedBy(jobId)
            .child(null, completed.get().childState()));
    Optional<ChangedJob> resumed = db.one(RESUME_PARENT_SQL, ChangedJob::from, parent);
    if (resumed.isPresent()) {
      db.dueNow(resumed.get().name());
      changes.add(
          Change.job(
                  parent,
                  JobAction.RESUMED,
                  JobState.SCHEDULED,
                  resumed.get().attempts(),
                  ActorType.NODE)
              .causedBy(jobId));
    }
    history.record(db, changes);
  }

  /** A row of {@code COMPLETE_CHILD_JOB_STEP_SQL}. */
  private record CompletedChildStep(UUID parentJobId, UUID stepId, String childState) {}

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
          List<UUID> cancelledSteps =
              db.list(CANCEL_STEPS_SQL, row -> row.uuid("id"), jobId, jobId, lock.token());
          history.record(
              db,
              cancelledSteps.stream()
                  .map(
                      step ->
                          Change.step(
                              jobId,
                              step,
                              StepAction.CANCELLED,
                              StepState.CANCELLED,
                              ActorType.NODE))
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
            db.dueAt(cancelled.get().name(), cancelled.get().scheduledAt());
          }
          history.record(
              db,
              Change.job(
                      jobId,
                      state == JobState.CANCELLED ? JobAction.CANCELLED : JobAction.COMPENSATING,
                      state,
                      cancelled.get().attempts(),
                      ActorType.NODE)
                  .message(message));
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
   * @param actor the user cancelling it, {@code null} for the application
   * @return {@code false} if the job does not exist, has ended or is compensating
   */
  public boolean requestCancel(UUID jobId, @Nullable String message, @Nullable Actor actor) {
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
              db.dueNow(job.get().name()); // to be cancelled by the next claim
            }
            history.record(
                db,
                Change.job(
                        jobId,
                        JobAction.CANCEL_REQUESTED,
                        job.get().state(),
                        job.get().attempts(),
                        ActorType.APPLICATION)
                    .actor(actor)
                    .message(message));
          }
          return job.isPresent();
        },
        e -> new BikeschedException("Cancelling bikesched job " + jobId + " failed", e));
  }

  /**
   * Schedules a {@code FAILED} job to run again right away, see {@code RETRY_JOB_SQL}.
   *
   * @param jobId the job
   * @param actor the user retrying it, {@code null} for the application
   * @return whether the job was failed and is now scheduled
   * @throws IllegalArgumentException if the job is an occurrence of a recurring job
   */
  public boolean retryJob(UUID jobId, @Nullable Actor actor) {
    return history.change(
        db -> {
          Optional<ChangedJob> job = db.one(RETRY_JOB_SQL, ChangedJob::from, jobId);
          if (job.isPresent()) {
            db.dueNow(job.get().name());
            history.record(
                db,
                Change.job(
                        jobId,
                        JobAction.RETRIED,
                        job.get().state(),
                        job.get().attempts(),
                        ActorType.APPLICATION)
                    .actor(actor));
            return true;
          }
          Optional<NotRetryable> notRetryable =
              db.one(
                  FIND_NOT_RETRYABLE_SQL,
                  row -> new NotRetryable(row.string("name"), row.uuid("parent_job_id")),
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
                    + notRetryable.get().name()
                    + " and cannot be retried; its next occurrence runs as scheduled");
          }
          return false;
        },
        e -> new BikeschedException("Retrying bikesched job " + jobId + " failed", e));
  }

  /** A row of {@code FIND_NOT_RETRYABLE_SQL}: an occurrence, or a child job. */
  private record NotRetryable(String name, UUID parentJobId) {}

  /** The state, attempt count, name and due time of a job after a change. */
  private record ChangedJob(JobState state, int attempts, String name, Instant scheduledAt) {

    static ChangedJob from(Sql.Row row) throws SQLException {
      return new ChangedJob(
          row.enumValue("state", JobState.class),
          row.integer("attempts"),
          row.string("name"),
          row.instant("scheduled_at"));
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
            db.dueAt(ended.get().name(), ended.get().scheduledAt());
          }
          history.record(
              db,
              Change.job(
                  jobId, JobAction.COMPENSATED, state, ended.get().attempts(), ActorType.NODE));
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
                      JobAction.COMPENSATION_FAILED,
                      job.state(),
                      job.attempts(),
                      ActorType.NODE)
                  .error(error)
                  .dueAt(job.state().isEnded() ? null : job.scheduledAt()));
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
        db -> insertOccurrence(db, occurrence, ActorType.NODE),
        e ->
            new BikeschedException(
                "Scheduling an occurrence of recurring job " + occurrence.name() + " failed", e));
  }

  private boolean insertOccurrence(Sql db, NewJob occurrence, ActorType actor) throws SQLException {
    UUID jobId = UuidUtil.timeOrdered();
    if (db.update(INSERT_OCCURRENCE_SQL, insertParameters(jobId, occurrence)) == 0) {
      return false;
    }
    db.due(occurrence.name(), occurrence.scheduledAt());
    history.record(db, created(jobId, occurrence, actor));
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
          boolean created = insertOccurrence(db, first, ActorType.APPLICATION);
          Optional<Moved> moved =
              created
                  ? Optional.empty()
                  : db.one(
                      RESCHEDULE_OCCURRENCE_SQL,
                      row ->
                          new Moved(
                              row.string("recurrence"), row.uuid("id"), row.integer("attempts")),
                      first.recurrence(),
                      first.scheduledAt(),
                      Sql.timestamp(first.plannedRunAt()),
                      first.name(),
                      first.recurrence());
          if (moved.isPresent()) {
            db.dueAt(first.name(), first.scheduledAt());
            history.record(
                db,
                Change.job(
                        moved.get().jobId(),
                        JobAction.MOVED,
                        JobState.SCHEDULED,
                        moved.get().attempts(),
                        ActorType.APPLICATION)
                    .actor(first.actor())
                    .recurrence(first.recurrence(), moved.get().previousRecurrence())
                    .dueAt(first.scheduledAt()));
          }
          String previousRecurrence = moved.map(Moved::previousRecurrence).orElse(null);
          return db.one(
                  FIND_PENDING_OCCURRENCE_SQL,
                  row ->
                      new RecurringRegistration(
                          row.uuid("id"),
                          row.instant("scheduled_at"),
                          row.string("state"),
                          created,
                          previousRecurrence),
                  first.name())
              // Cannot happen within this transaction: the insert either succeeded or conflicted.
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No pending occurrence of recurring job " + first.name()));
        },
        e -> new BikeschedException("Registering recurring job " + first.name() + " failed", e));
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
   * @param name the job name of the recurring job
   * @param actor the user removing it, {@code null} for the application
   * @return the number of deleted occurrences
   */
  public int deletePendingOccurrences(String name, @Nullable Actor actor) {
    return history.change(
        db -> {
          List<Change> deleted =
              db.list(
                  DELETE_PENDING_OCCURRENCES_SQL,
                  row ->
                      Change.job(
                              row.uuid("id"),
                              JobAction.DELETED,
                              row.enumValue("state", JobState.class),
                              row.integer("attempts"),
                              ActorType.APPLICATION)
                          .actor(actor)
                          .subjectName(row.string("name")),
                  name);
          history.record(db, deleted);
          return deleted.size();
        },
        e -> new BikeschedException("Removing recurring job " + name + " failed", e));
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
          JobAction action =
              switch (job.state()) {
                case SCHEDULED -> JobAction.ATTEMPT_FAILED;
                case COMPENSATING -> JobAction.COMPENSATING;
                case FAILING -> JobAction.FAILING;
                case FAILED, RUNNING, WAITING, SUCCEEDED, CANCELLED, COMPENSATION_FAILED ->
                    JobAction.FAILED;
              };
          history.record(
              db,
              Change.job(jobId, action, job.state(), job.attempts(), ActorType.NODE)
                  .error(error)
                  .dueAt(job.state() == JobState.SCHEDULED ? job.scheduledAt() : null));
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
   * @param reason why it ends, recorded in the history: {@code NO_FAILURE_HANDLER}, {@code
   *     FAILURE_HANDLER_RAN} or {@code FAILURE_HANDLER_GAVE_UP}
   * @param error the failure handler's last error if it gave up, {@code null} otherwise
   * @return the job's new state; empty if the job is not failing on this node (anymore)
   */
  public Optional<EndedJob> endFailing(ClaimEnd end, String reason, @Nullable String error) {
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
              db,
              Change.job(
                      jobId,
                      state == JobState.FAILED ? JobAction.FAILED : JobAction.COMPENSATION_FAILED,
                      state,
                      ended.get().attempts(),
                      ActorType.NODE)
                  .reason(reason)
                  .error(error));
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
          db.dueAt(failing.get().name(), failing.get().scheduledAt());
          history.record(
              db,
              Change.job(
                      jobId,
                      JobAction.FAILURE_HANDLER_FAILED,
                      JobState.FAILING,
                      failing.get().attempts(),
                      ActorType.NODE)
                  .error(error)
                  .dueAt(failing.get().scheduledAt()));
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
   * @param name its job name
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  public record FailedJob(
      JobState state,
      int maxAttempts,
      Instant scheduledAt,
      int attempts,
      String name,
      boolean nextOccurrenceScheduled) {

    /** Maps a row of {@code FAIL_JOB_SQL} or {@code FAIL_COMPENSATION_SQL}. */
    static FailedJob from(Sql.Row row) throws SQLException {
      return new FailedJob(
          row.enumValue("state", JobState.class),
          row.integer("max_attempts"),
          row.instant("scheduled_at"),
          row.integer("attempts"),
          row.string("name"),
          false);
    }

    FailedJob withNextOccurrence(boolean scheduled) {
      return new FailedJob(state, maxAttempts, scheduledAt, attempts, name, scheduled);
    }

    /**
     * A retried, compensating or failing job is due again (right away or after its delay): the
     * failing node wakes itself up for it, other nodes are not told.
     */
    void reportDue(Sql db) {
      if (!state.isEnded()) {
        db.dueAt(name, scheduledAt);
      }
    }
  }

  /** The history entry of a job released from its node, a row of a {@code RELEASE_…_SQL}. */
  private static Change released(Sql.Row row, String reason) throws SQLException {
    return Change.job(
            row.uuid("id"),
            JobAction.RELEASED,
            row.enumValue("state", JobState.class),
            row.integer("attempts"),
            ActorType.NODE)
        .reason(reason);
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
                    db.dueNow(row.string("name"));
                    return released(row, "NODE_STOPPED");
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
                    db.dueNow(row.string("name"));
                    return released(row, "OUTCOME_NOT_RECORDED");
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
                    db.dueNow(row.string("name"));
                    return new ReleasedJob(
                        row.uuid("node_id"),
                        row.string("node_name"),
                        row.uuid("job_id"),
                        row.enumValue("state", JobState.class),
                        row.integer("attempts"));
                  },
                  callingNodeId,
                  Sql.seconds(nodeTimeout));
          List<Change> changes = new ArrayList<>();
          Map<UUID, DeadNodeJobs> perNode = new LinkedHashMap<>();
          for (ReleasedJob job : released) {
            changes.add(
                Change.job(
                        job.jobId(),
                        JobAction.RELEASED,
                        job.state(),
                        job.attempts(),
                        ActorType.NODE)
                    .causedBy(job.nodeId())
                    .reason("NODE_DEAD"));
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
   * The channel on which nodes announce job names with jobs due now (the payload is the name), see
   * {@link DueJobs#dueNow}.
   */
  public static final String CHANNEL = "bikesched_jobs";

  // One notification per name; PostgreSQL also merges equal notifications of a transaction. Sent
  // by the notifier in an auto-commit statement of its own (a transaction issuing NOTIFY takes a
  // database-wide lock at commit, so job writes never notify), and inside a caller's transaction
  // for writes on the caller's connection (sent on commit).
  static final String NOTIFY_DUE_SQL =
      "select pg_notify('" + CHANNEL + "', t) from unnest(?::text[]) t";

  // How long until the earliest job of the names is due, within the window only: bounded, the
  // lookup reads the poll index entries of the window instead of all delayed jobs. The database
  // computes the delay, so the nodes' clocks don't matter.
  private static final String NEXT_DUE_SQL =
      """
            select ceil(extract(epoch from min(scheduled_at) - now()) * 1000)::bigint as due_in
              from bikesched_jobs
             where state in ('SCHEDULED', 'WAITING', 'COMPENSATING', 'FAILING')
               and scheduled_at > now() and scheduled_at <= now() + ? * interval '1 millisecond'
               and name = any(?)""";

  /**
   * Notifies all listening nodes that jobs of these names are due now, in an auto-commit statement
   * of its own.
   *
   * @param names the job names (any text: nodes ignore names they don't handle)
   */
  public void notifyDue(Collection<String> names) {
    access.withConnection(
        db -> {
          db.execute(NOTIFY_DUE_SQL, Sql.array("text", names.toArray()));
          return null;
        },
        e -> new BikeschedException("Notifying due jobs failed", e));
  }

  /**
   * How long until the earliest job of the given names becomes due, if one does within the window
   * (jobs due already don't count).
   *
   * @param names the job names
   * @param window how far to look ahead
   * @return the delay, empty if no job of the names becomes due within the window
   */
  public Optional<Duration> nextDueIn(Collection<String> names, Duration window) {
    return access
        .withConnection(
            db ->
                db.one(
                    NEXT_DUE_SQL,
                    row -> Optional.ofNullable(row.millis("due_in")),
                    window.toMillis(),
                    Sql.array("text", names.toArray())),
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
            select count(*) as count, max(finished_at) as last, (select at from cutoff) as cutoff
              from deleted""";

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
   * @param name the job name
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
   * @param reference the external entity it is about, {@code null} for none
   */
  public record ClaimedJob(
      UUID id,
      String name,
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
      @Nullable String lastError,
      @Nullable String reference) {

    /** Maps a row of {@code CLAIM_JOBS_SQL}. */
    static ClaimedJob from(Sql.Row row) throws SQLException {
      int attempts = row.integer("attempts");
      JobState compensatedState = row.enumValue("compensation_end_state", JobState.class);
      JobState failingState = row.enumValue("failure_end_state", JobState.class);
      JobPhase phase;
      if (failingState != null) {
        phase = new JobPhase.HandleFailure(failingState, row.integer("failure_attempts"));
      } else if (compensatedState != null) {
        phase = new JobPhase.Compensate(compensatedState, row.integer("compensation_attempts"));
      } else {
        phase = new JobPhase.Run(attempts);
      }
      return new ClaimedJob(
          row.uuid("id"),
          row.string("name"),
          row.string("payload"),
          row.integer("priority"),
          row.instant("scheduled_at"),
          attempts,
          row.integer("max_attempts"),
          row.millis("timeout_millis"),
          phase,
          JobOrigin.of(
              row.uuid("parent_job_id"), row.string("recurrence"), row.instant("planned_run_at")),
          row.bool("cancel_requested"),
          row.string("cancel_message"),
          new JobLock(row.uuid("locked_by_node_id"), row.uuid("lock_token")),
          row.string("last_error"),
          row.string("reference"));
    }
  }
}
