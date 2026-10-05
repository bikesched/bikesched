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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobState;

/**
 * The {@code bikesched_jobs} table: inserting, claiming and finishing jobs, releasing the jobs of
 * stopped or dead nodes, and the occurrences of recurring jobs. Changes are recorded in the {@link
 * HistoryStore job history}, if enabled. Thread-safe; every call borrows its own connection from
 * the {@link DatabaseAccess}.
 */
public final class JobStore {

  private final DatabaseAccess access;
  private final HistoryStore history;

  JobStore(DatabaseAccess access, HistoryStore history) {
    this.access = Objects.requireNonNull(access, "access");
    this.history = Objects.requireNonNull(history, "history");
  }

  private static final String INSERT_JOB_SQL =
      """
            insert into bikesched_jobs (id, job_type, payload, scheduled_at, priority, max_attempts, timeout_millis, recurrence, fixed_rate_at)
            values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""";

  // Inserts an occurrence of a recurring job unless one is pending already (partial unique index).
  private static final String INSERT_OCCURRENCE_SQL =
      INSERT_JOB_SQL
          + """

            on conflict (job_type) where recurrence is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING')
            do nothing""";

  // Moves a pending (not yet running) occurrence to a new recurrence; returns the old description.
  private static final String RESCHEDULE_OCCURRENCE_SQL =
      """
            update bikesched_jobs j
               set recurrence = ?, scheduled_at = ?, fixed_rate_at = ?, updated_at = now()
              from (select id, recurrence from bikesched_jobs
                     where job_type = ? and recurrence is not null and state = 'SCHEDULED'
                       and recurrence <> ?
                     for update) old
             where j.id = old.id
            returning old.recurrence, j.id, j.attempts""";

  private static final String FIND_PENDING_OCCURRENCE_SQL =
      """
            select id, scheduled_at, state from bikesched_jobs
             where job_type = ? and recurrence is not null and state in ('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING')""";

  // Running occurrences cannot be deleted; finishing them schedules no successor on nodes that no
  // longer have the recurring job registered.
  private static final String DELETE_PENDING_OCCURRENCES_SQL =
      """
            delete from bikesched_jobs
             where job_type = ? and recurrence is not null and state in ('SCHEDULED', 'WAITING')
            returning id, state, attempts""";

  // Claims due jobs in one statement: rows locked by other nodes' concurrent claims are skipped
  // (instead of waiting), and claimed rows are marked RUNNING for this node before the commit
  // releases the row locks, so no other node can pick them up afterwards. Claiming a COMPENSATING
  // job counts a compensation attempt instead of an attempt (compensated_state stays set). Only
  // jobs of types the node has handlers for: during a rolling deploy, nodes of the old version
  // leave
  // jobs of new types to the new ones (job_type is a key column of the polling index, so the filter
  // is checked on index entries, in claim order, without visiting other rows).
  private static final String CLAIM_JOBS_SQL =
      """
            update bikesched_jobs
               set state = 'RUNNING', locked_by = ?, lock_token = gen_random_uuid(), locked_at = now(),
                   attempts = attempts + case when compensated_state is null then 1 else 0 end,
                   compensation_attempts = compensation_attempts + case when compensated_state is null then 0 else 1 end,
                   updated_at = now()
             where id in (select id from bikesched_jobs
                           where state in ('SCHEDULED', 'WAITING', 'COMPENSATING') and scheduled_at <= now()
                             and job_type = any(?)
                           order by priority desc, scheduled_at
                           limit ?
                           for update skip locked)
            returning id, job_type, payload::text, priority, scheduled_at, attempts, max_attempts, timeout_millis, recurrence, fixed_rate_at,
                      compensated_state, compensation_attempts, cancel_requested_at is not null, cancel_message,
                      locked_by, lock_token""";

  private static final String FIND_JOB_SQL =
      """
            select job_type, state from bikesched_jobs where id = ?""";

  private static final String COMPLETE_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SUCCEEDED', locked_by = null, lock_token = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING'
            returning attempts""";

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
                   locked_by = null, lock_token = null, locked_at = null, last_error = ?, updated_at = now()
              from %s
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensated_state is null
            returning j.state, j.attempts"""
          .formatted(COMPENSATE_SQL);

  // The unfinished steps of a job being cancelled by this node (waiting for an event, or left
  // running by a crash) are cancelled too: they no longer receive events. Runs before
  // CANCEL_JOB_SQL in its transaction, locking the steps before the job like publishing an event
  // does (EventStore.RESUME_WAITING_STEPS_SQL), so the two cannot deadlock.
  private static final String CANCEL_STEPS_SQL =
      """
            update bikesched_steps set state = 'CANCELLED', locked_by = null, finished_at = now()
             where job_id = ? and state in ('WAITING', 'RECEIVED', 'RUNNING')
               and exists (select 1 from bikesched_jobs
                            where id = ? and lock_token = ? and state = 'RUNNING' and compensated_state is null)
            returning name""";

  // Bikesched.retry(jobId): a FAILED job runs again with all its attempts, keeping its completed
  // steps (compensated ones are COMPENSATED and run again). Not for occurrences of recurring jobs
  // (FIND_RECURRING_TYPE_SQL tells them apart when nothing was retried).
  private static final String RETRY_JOB_SQL =
      """
            update bikesched_jobs
               set state = 'SCHEDULED', attempts = 0, compensated_state = null, compensation_attempts = 0,
                   cancel_requested_at = null, cancel_message = null,
                   scheduled_at = now(), finished_at = null, updated_at = now()
             where id = ? and state = 'FAILED' and recurrence is null
            returning state, attempts""";

  private static final String FIND_RECURRING_TYPE_SQL =
      """
            select job_type from bikesched_jobs where id = ? and recurrence is not null""";

  // Bikesched.cancel(jobId): marks the job; the node claiming it next cancels it instead of running
  // its handler. Scheduled and waiting jobs are due right away; a running job is cancelled after
  // its current attempt, unless that attempt completes it (or fails it permanently).
  private static final String REQUEST_CANCEL_SQL =
      """
            update bikesched_jobs
               set cancel_requested_at = coalesce(cancel_requested_at, now()), cancel_message = ?,
                   scheduled_at = case when state in ('SCHEDULED', 'WAITING') then now() else scheduled_at end,
                   updated_at = now()
             where id = ? and state in ('SCHEDULED', 'WAITING', 'RUNNING') and compensated_state is null
            returning state, attempts""";

  // All compensations of a compensating job ran: it ends in the state it was compensated for.
  private static final String COMPLETE_COMPENSATION_SQL =
      """
            update bikesched_jobs
               set state = compensated_state, locked_by = null, lock_token = null, locked_at = null, finished_at = now(), updated_at = now()
             where id = ? and lock_token = ? and state = 'RUNNING' and compensated_state is not null
            returning state, attempts""";

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
                   locked_by = null, lock_token = null, locked_at = null, updated_at = now()
              from (values (?::text, ?::bigint)) as p (error, retry_after_millis)
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensated_state is not null
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts""";

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
                   locked_by = null, lock_token = null, locked_at = null, last_error = p.error, updated_at = now()
              from (values (?::text, ?::integer, ?::bigint)) as p (error, max_attempts, retry_after_millis),
                   %s
             where j.id = ? and j.lock_token = ? and j.state = 'RUNNING' and j.compensated_state is null
            returning j.state, j.max_attempts, j.scheduled_at, j.attempts"""
          .formatted(COMPENSATE_SQL);

  private static final String RELEASE_JOBS_SQL =
      """
            update bikesched_jobs
               set state = case when compensated_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by = null, lock_token = null, locked_at = null, updated_at = now()
             where locked_by = ? and state = 'RUNNING'
            returning id, state, attempts""";

  // Releases RUNNING jobs (back to SCHEDULED, or COMPENSATING) of nodes whose heartbeat is older
  // than the timeout (excluding the calling node); returns one row per released job with its dead
  // node. Concurrent callers on other nodes are safe: the second one blocks on the row locks and
  // then no longer matches state = 'RUNNING'.
  private static final String RELEASE_JOBS_OF_DEAD_NODES_SQL =
      """
            update bikesched_jobs j
               set state = case when j.compensated_state is null then 'SCHEDULED' else 'COMPENSATING' end,
                   locked_by = null, lock_token = null, locked_at = null, updated_at = now()
              from bikesched_nodes n
             where j.locked_by = n.id
               and j.state = 'RUNNING'
               and n.id <> ?
               and n.last_heartbeat_at < now() - make_interval(secs => ?)
            returning n.id, n.name, j.id, j.state, j.attempts""";

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
   * Inserts a new {@code SCHEDULED} job.
   *
   * @param jobId the job id
   * @param job the job's attributes
   */
  public void insertJob(UUID jobId, NewJob job) {
    history.change(
        db -> insertJob(db, jobId, job),
        e ->
            new BikeschedException(
                "Scheduling bikesched job of type '" + job.type() + "' failed", e));
  }

  /**
   * Inserts a new scheduled job using the caller's connection, as part of the caller's transaction:
   * the connection is neither committed nor closed.
   *
   * @param connection the caller's connection
   * @param jobId the new job's id
   * @param job the job's attributes
   */
  public void insertJob(Connection connection, UUID jobId, NewJob job) {
    access.onCallerConnection(
        connection,
        db -> insertJob(db, jobId, job),
        e ->
            new BikeschedException(
                "Scheduling bikesched job of type '" + job.type() + "' failed", e));
  }

  private Void insertJob(Sql db, UUID jobId, NewJob job) throws SQLException {
    db.update(INSERT_JOB_SQL, insertParameters(jobId, job));
    history.record(db, created(jobId, job));
    return null;
  }

  /** The history entry of a new job (or occurrence). */
  private static Change created(UUID jobId, NewJob job) {
    String due = "due at " + job.scheduledAt();
    return Change.job(
        jobId,
        "SCHEDULED",
        0,
        null,
        job.recurrence() == null ? due : "occurrence of '" + job.recurrence() + "', " + due);
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
      Sql.timestamp(job.fixedRateAt())
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
                row -> new DefaultJobInfo(jobId, row.string(1), JobState.valueOf(row.string(2))),
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
              history.record(
                  db,
                  claimed.stream()
                      .map(
                          job ->
                              Change.job(
                                  job.id(), "RUNNING", job.attempts(), nodeId, claimDetail(job)))
                      .toList());
              return claimed;
            },
            e ->
                new BikeschedException(
                    "Claiming jobs for bikesched node " + nodeId + " failed", e));
    jobs.sort(CLAIM_ORDER);
    return jobs;
  }

  private static String claimDetail(ClaimedJob job) {
    if (job.compensatedState() != null) {
      return "compensation attempt " + job.compensationAttempts();
    }
    return job.cancelRequested() ? "cancellation requested" : null;
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED}.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @return {@code false} if the job is not running on this node (anymore)
   */
  public boolean completeJob(UUID jobId, JobLock lock) {
    return history.change(
        db -> completeJob(db, jobId, lock),
        e -> new BikeschedException("Completing bikesched job " + jobId + " failed", e));
  }

  private boolean completeJob(Sql db, UUID jobId, JobLock lock) throws SQLException {
    Optional<Integer> attempts =
        db.one(COMPLETE_JOB_SQL, row -> row.integer(1), jobId, lock.token());
    if (attempts.isPresent()) {
      history.record(db, Change.job(jobId, "SUCCEEDED", attempts.get(), lock.nodeId(), null));
    }
    return attempts.isPresent();
  }

  /**
   * Marks a job claimed by the node as {@code SUCCEEDED} and, in the same transaction, inserts the
   * next occurrence of its recurring job.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param next the next occurrence, {@code null} if none
   * @return empty if the job is not running on this node (anymore); otherwise whether the next
   *     occurrence was inserted (not if one is pending already)
   */
  public Optional<Boolean> completeJob(UUID jobId, JobLock lock, NewJob next) {
    if (next == null) {
      return completeJob(jobId, lock) ? Optional.of(false) : Optional.empty();
    }
    return access.inTransaction(
        db -> {
          if (!completeJob(db, jobId, lock)) {
            return Optional.<Boolean>empty();
          }
          return Optional.of(insertOccurrence(db, next));
        },
        e -> new BikeschedException("Completing bikesched job " + jobId + " failed", e));
  }

  /**
   * Marks a job claimed by the node as {@code CANCELLED}; it is not retried.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param message why the job was cancelled, stored as {@code last_error}; may be {@code null}
   * @return {@code false} if the job is not running on this node (anymore)
   */
  public boolean cancelJob(UUID jobId, JobLock lock, String message) {
    return cancelJob(jobId, lock, message, true, null).isPresent();
  }

  /**
   * Cancels a job claimed by the node: marks it {@code CANCELLED}, or {@code COMPENSATING} if it
   * has completed steps to compensate, and its unfinished steps {@code CANCELLED}. If the job ends
   * {@code CANCELLED}, the next occurrence of its recurring job is inserted in the same transaction
   * (cancelling cancels this occurrence only).
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param message why the job was cancelled, may be {@code null}
   * @param attemptRan whether the handler ran in the claimed attempt; if not (cancelled on request
   *     right after claiming it), the attempt is not counted
   * @param next the next occurrence, {@code null} if none
   * @return the job's new state; empty if the job is not running on this node (anymore)
   */
  public Optional<EndedJob> cancelJob(
      UUID jobId, JobLock lock, String message, boolean attemptRan, NewJob next) {
    return access.inTransaction(
        db -> {
          List<String> cancelledSteps =
              db.list(CANCEL_STEPS_SQL, row -> row.string(1), jobId, jobId, lock.token());
          history.record(
              db,
              cancelledSteps.stream()
                  .map(step -> Change.step(jobId, step, "CANCELLED", lock.nodeId(), null))
                  .toList());
          Optional<StateAndAttempts> cancelled =
              db.one(
                  CANCEL_JOB_SQL,
                  StateAndAttempts::from,
                  attemptRan ? 0 : 1,
                  ValidationUtil.truncateMessage(message),
                  jobId,
                  jobId,
                  lock.token());
          if (cancelled.isEmpty()) {
            return Optional.<EndedJob>empty();
          }
          String state = cancelled.get().state();
          history.record(
              db, Change.job(jobId, state, cancelled.get().attempts(), lock.nodeId(), message));
          boolean scheduled =
              next != null && state.equals("CANCELLED") && insertOccurrence(db, next);
          return Optional.of(new EndedJob(state, scheduled));
        },
        e -> new BikeschedException("Cancelling bikesched job " + jobId + " failed", e));
  }

  /**
   * A job after its attempt ended it, or started compensating it.
   *
   * @param state its new state, e.g. {@code CANCELLED} or {@code COMPENSATING}
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  public record EndedJob(String state, boolean nextOccurrenceScheduled) {

    public boolean compensating() {
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
  public boolean requestCancel(UUID jobId, String message) {
    return history.change(
        db -> {
          Optional<StateAndAttempts> job =
              db.one(
                  REQUEST_CANCEL_SQL,
                  StateAndAttempts::from,
                  ValidationUtil.truncateMessage(message),
                  jobId);
          if (job.isPresent()) {
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
          Optional<StateAndAttempts> job = db.one(RETRY_JOB_SQL, StateAndAttempts::from, jobId);
          if (job.isPresent()) {
            history.record(
                db, Change.job(jobId, job.get().state(), job.get().attempts(), null, "retried"));
            return true;
          }
          Optional<String> recurringType =
              db.one(FIND_RECURRING_TYPE_SQL, row -> row.string(1), jobId);
          if (recurringType.isPresent()) {
            throw new IllegalArgumentException(
                "Job "
                    + jobId
                    + " is an occurrence of recurring job "
                    + recurringType.get()
                    + " and cannot be retried; its next occurrence runs as scheduled");
          }
          return false;
        },
        e -> new BikeschedException("Retrying bikesched job " + jobId + " failed", e));
  }

  /** The state and attempt count of a job after a change. */
  private record StateAndAttempts(String state, int attempts) {

    static StateAndAttempts from(Sql.Row row) throws SQLException {
      return new StateAndAttempts(row.string(1), row.integer(2));
    }
  }

  /**
   * Ends a compensating job claimed by the node, once all its compensations ran, in the state it
   * was compensated for ({@code CANCELLED} or {@code FAILED}); inserts the next occurrence of its
   * recurring job in the same transaction.
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param next the next occurrence, {@code null} if none
   * @return the job's new state; empty if the job is not compensating on this node (anymore)
   */
  public Optional<EndedJob> completeCompensation(UUID jobId, JobLock lock, NewJob next) {
    return access.inTransaction(
        db -> {
          Optional<StateAndAttempts> ended =
              db.one(COMPLETE_COMPENSATION_SQL, StateAndAttempts::from, jobId, lock.token());
          if (ended.isEmpty()) {
            return Optional.<EndedJob>empty();
          }
          history.record(
              db,
              Change.job(
                  jobId,
                  ended.get().state(),
                  ended.get().attempts(),
                  lock.nodeId(),
                  "compensated"));
          boolean scheduled = next != null && insertOccurrence(db, next);
          return Optional.of(new EndedJob(ended.get().state(), scheduled));
        },
        e ->
            new BikeschedException(
                "Completing the compensation of bikesched job " + jobId + " failed", e));
  }

  /**
   * Records a failed compensation attempt of a job claimed by the node: compensating again while
   * compensation attempts remain, otherwise {@code COMPENSATION_FAILED} (then inserting the next
   * occurrence of its recurring job in the same transaction).
   *
   * @param jobId the job id
   * @param lock the lock of the claim (node and token)
   * @param error description of the failure, stored as {@code last_error} once final
   * @param retryAfter delay of the next compensation attempt, {@code null} for as soon as possible
   * @param next the next occurrence, {@code null} if none
   * @return the job after recording the failure; empty if it is not compensating on this node
   */
  public Optional<FailedJob> failCompensation(
      UUID jobId, JobLock lock, String error, Duration retryAfter, NewJob next) {
    return access.inTransaction(
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_COMPENSATION_SQL,
                  FailedJob::from,
                  ValidationUtil.truncateMessage(error),
                  Sql.millis(retryAfter),
                  jobId,
                  lock.token());
          if (failed.isPresent()) {
            history.record(
                db,
                Change.job(
                    jobId,
                    failed.get().state(),
                    failed.get().attempts(),
                    lock.nodeId(),
                    "compensation failed: " + error));
          }
          if (next != null && failed.isPresent() && failed.get().ended()) {
            return Optional.of(failed.get().withNextOccurrence(insertOccurrence(db, next)));
          }
          return failed;
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
    UUID jobId = UUID.randomUUID();
    if (db.update(INSERT_OCCURRENCE_SQL, insertParameters(jobId, occurrence)) == 0) {
      return false;
    }
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
                      Sql.timestamp(first.fixedRateAt()),
                      first.type(),
                      first.recurrence());
          if (moved.isPresent()) {
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
      UUID jobId, Instant scheduledAt, String state, boolean created, String previousRecurrence) {}

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
      UUID jobId, JobLock lock, String error, Integer maxAttempts, Duration retryAfter) {
    return failJob(jobId, lock, error, maxAttempts, retryAfter, null);
  }

  /**
   * Records a failed attempt like {@link #failJob(UUID, UUID, String, Integer, Duration)}; if the
   * job fails permanently, the next occurrence of its recurring job is inserted in the same
   * transaction.
   *
   * @param next the next occurrence, {@code null} if none
   */
  public Optional<FailedJob> failJob(
      UUID jobId,
      JobLock lock,
      String error,
      Integer maxAttempts,
      Duration retryAfter,
      NewJob next) {
    return access.inTransaction(
        db -> {
          Optional<FailedJob> failed =
              db.one(
                  FAIL_JOB_SQL,
                  FailedJob::from,
                  ValidationUtil.truncateMessage(error),
                  Sql.typed(maxAttempts, Types.INTEGER),
                  Sql.millis(retryAfter),
                  jobId,
                  jobId,
                  lock.token());
          if (failed.isPresent()) {
            FailedJob job = failed.get();
            history.record(
                db,
                Change.job(
                    jobId,
                    job.state(),
                    job.attempts(),
                    lock.nodeId(),
                    job.retried() ? error + " (retry at " + job.scheduledAt() + ")" : error));
          }
          if (next != null && failed.isPresent() && failed.get().ended()) {
            boolean scheduled = insertOccurrence(db, next);
            return Optional.of(failed.get().withNextOccurrence(scheduled));
          }
          return failed;
        },
        e -> new BikeschedException("Recording failure of bikesched job " + jobId + " failed", e));
  }

  /**
   * A job after recording a failed attempt or compensation attempt.
   *
   * @param state {@code SCHEDULED} (retried), {@code COMPENSATING} (compensating now, or again),
   *     {@code FAILED} or {@code COMPENSATION_FAILED}
   * @param maxAttempts its maximum number of (compensation) attempts
   * @param scheduledAt when it runs next (only meaningful unless it ended)
   * @param attempts its number of attempts
   * @param nextOccurrenceScheduled whether the next occurrence of its recurring job was inserted
   */
  public record FailedJob(
      String state,
      int maxAttempts,
      Instant scheduledAt,
      int attempts,
      boolean nextOccurrenceScheduled) {

    public boolean retried() {
      return state.equals("SCHEDULED");
    }

    public boolean compensating() {
      return state.equals("COMPENSATING");
    }

    /** Whether the job ended ({@code FAILED} or {@code COMPENSATION_FAILED}). */
    public boolean ended() {
      return state.equals("FAILED") || state.equals("COMPENSATION_FAILED");
    }

    /** Maps a row of {@code FAIL_JOB_SQL} or {@code FAIL_COMPENSATION_SQL}. */
    static FailedJob from(Sql.Row row) throws SQLException {
      return new FailedJob(row.string(1), row.integer(2), row.instant(3), row.integer(4), false);
    }

    FailedJob withNextOccurrence(boolean scheduled) {
      return new FailedJob(state, maxAttempts, scheduledAt, attempts, scheduled);
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
                  row ->
                      Change.job(
                          row.uuid(1),
                          row.string(2),
                          row.integer(3),
                          nodeId,
                          "released by its node (stopped or restarted)"),
                  nodeId);
          history.record(db, released);
          return released.size();
        },
        e -> new BikeschedException("Releasing jobs of bikesched node " + nodeId + " failed", e));
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
                  row ->
                      new ReleasedJob(
                          row.uuid(1), row.string(2), row.uuid(3), row.string(4), row.integer(5)),
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

  // A batch of ended jobs past the retention; their steps go with them (on delete cascade). Skips
  // locked rows, so concurrent clean-ups on several nodes delete different jobs.
  private static final String DELETE_FINISHED_JOBS_SQL =
      """
            delete from bikesched_jobs
             where id in (select id from bikesched_jobs
                           where finished_at < now() - make_interval(secs => ?)
                             and state in ('SUCCEEDED', 'FAILED', 'CANCELLED')
                           limit ? for update skip locked)""";

  /**
   * Deletes up to {@code limit} ended jobs ({@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED})
   * that finished longer than {@code retention} ago, with their steps. The job history is kept, it
   * has its own retention.
   *
   * @param retention how long ended jobs are kept
   * @param limit the maximum number of jobs to delete
   * @return the number of deleted jobs
   */
  public int deleteFinishedJobs(Duration retention, int limit) {
    return access.withConnection(
        db -> db.update(DELETE_FINISHED_JOBS_SQL, Sql.seconds(retention), limit),
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
   * @param recurrence the description of its recurring job's recurrence, {@code null} for a one-off
   *     job
   * @param fixedRateAt its planned run if its recurrence is fixed-rate, {@code null} otherwise
   * @param compensatedState {@code CANCELLED} or {@code FAILED} if the job was claimed to
   *     compensate its steps, {@code null} if it was claimed to run
   * @param compensationAttempts the number of compensation attempts including the claimed one
   * @param cancelRequested whether its cancellation was requested via {@code Bikesched.cancel}
   * @param cancelMessage the message of that request, may be {@code null}
   * @param lock the claiming node and the token of the claim; the attempt's writes are fenced by it
   */
  public record ClaimedJob(
      UUID id,
      String type,
      String payload,
      int priority,
      Instant scheduledAt,
      int attempts,
      int maxAttempts,
      Duration timeout,
      String recurrence,
      Instant fixedRateAt,
      String compensatedState,
      int compensationAttempts,
      boolean cancelRequested,
      String cancelMessage,
      JobLock lock) {

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
          row.string(14),
          new JobLock(row.uuid(15), row.uuid(16)));
    }
  }
}
