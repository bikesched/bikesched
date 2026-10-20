// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.internal.database.DatabaseAccess.SqlExceptionHandler;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobHistoryEntry;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_job_history} table: one row per change of a job or of one of its steps.
 *
 * <p>The other stores {@link #record record} their changes through this store, on the {@link Sql}
 * of the transaction making the change, so an entry commits or rolls back with it. With the history
 * disabled, recording does nothing and {@link #change} runs changes without an explicit
 * transaction, exactly as without this store. Thread-safe.
 */
public final class HistoryStore {

  private final DatabaseAccess access;
  private final boolean enabled;

  HistoryStore(DatabaseAccess access, boolean enabled) {
    this.access = access;
    this.enabled = enabled;
  }

  // One statement for any number of changes. Step changes take the job's attempt count and (if no
  // node is given) the node running the job from bikesched_jobs; job changes pass both. The
  // identity column numbers the rows in the order of the given changes.
  private static final String RECORD_SQL =
      """
            insert into bikesched_job_history (job_id, step_name, state, attempt, node_id, detail)
            select t.job_id, t.step_name, t.state,
                   coalesce(t.attempt, j.attempts, 0),
                   case when t.step_name is null then t.node_id else coalesce(t.node_id, j.locked_by) end,
                   t.detail
              from unnest(?::uuid[], ?::text[], ?::text[], ?::integer[], ?::uuid[], ?::text[])
                   with ordinality as t (job_id, step_name, state, attempt, node_id, detail, position)
              left join bikesched_jobs j on j.id = t.job_id
             order by t.position""";

  private static final String FIND_HISTORY_SQL =
      """
            select created_at, step_name, state, attempt, node_id, detail
              from bikesched_job_history where job_id = ? order by seq""";

  // A batch of entries past the retention, oldest first from the given created_at on (see
  // DeletedBatch). Skips locked rows, so concurrent clean-ups on several nodes delete different
  // entries.
  private static final String DELETE_HISTORY_SQL =
      """
            with cutoff as (select coalesce(?, now() - make_interval(secs => ?)) as at),
            batch as (
                select job_id, seq from bikesched_job_history
                 where created_at >= coalesce(?, '-infinity'::timestamptz)
                   and created_at < (select at from cutoff)
                 order by created_at
                 limit ? for update skip locked),
            deleted as (
                delete from bikesched_job_history h using batch b
                 where h.job_id = b.job_id and h.seq = b.seq
                returning h.created_at)
            select count(*), max(created_at), (select at from cutoff) from deleted""";

  /**
   * A change of a job or of one of its steps, to be recorded.
   *
   * @param jobId the job
   * @param stepName the step, {@code null} for a change of the job itself
   * @param state the new (or, for requests, unchanged) state
   * @param attempt the job's attempt count after the change; {@code null} to read it from the job
   * @param nodeId the node making the change; {@code null} for the application (for step changes:
   *     the node running the job)
   * @param detail what happened, may be {@code null}; truncated like other messages
   */
  public record Change(
      UUID jobId,
      @Nullable String stepName,
      String state,
      @Nullable Integer attempt,
      @Nullable UUID nodeId,
      @Nullable String detail) {

    /** A change of the job itself. */
    public static Change job(
        UUID jobId, String state, int attempt, @Nullable UUID nodeId, @Nullable String detail) {
      return new Change(jobId, null, state, attempt, nodeId, detail);
    }

    /** A change of the job itself. */
    public static Change job(
        UUID jobId, JobState state, int attempt, @Nullable UUID nodeId, @Nullable String detail) {
      return job(jobId, state.name(), attempt, nodeId, detail);
    }

    /** A change of a step, made by the node running the job (or the given node). */
    public static Change step(
        UUID jobId, String stepName, String state, @Nullable UUID nodeId, @Nullable String detail) {

      return new Change(jobId, stepName, state, null, nodeId, detail);
    }
  }

  /** Whether changes are recorded. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Runs a statement (or several) changing jobs or steps: in a transaction if the history is
   * enabled, so the recorded entries commit with the change, otherwise on a plain connection.
   */
  <T extends @Nullable Object> T change(Sql.Work<T> work, SqlExceptionHandler onFailure) {
    return enabled ? access.inTransaction(work, onFailure) : access.withConnection(work, onFailure);
  }

  /**
   * Records changes in the transaction of the given {@link Sql}; does nothing if the history is
   * disabled or there are no changes.
   *
   * @param db the transaction making the changes
   * @param changes the changes, in the order they were made
   */
  void record(Sql db, List<Change> changes) throws SQLException {
    if (!enabled || changes.isEmpty()) {
      return;
    }
    int size = changes.size();
    var jobIds = new UUID[size];
    var stepNames = new @Nullable String[size];
    var states = new String[size];
    var attempts = new @Nullable Integer[size];
    var nodeIds = new @Nullable UUID[size];
    var details = new @Nullable String[size];
    for (int i = 0; i < size; i++) {
      Change change = changes.get(i);
      jobIds[i] = change.jobId();
      stepNames[i] = change.stepName();
      states[i] = change.state();
      attempts[i] = change.attempt();
      nodeIds[i] = change.nodeId();
      details[i] = ValidationUtil.truncateMessage(change.detail());
    }
    db.update(
        RECORD_SQL,
        Sql.array("uuid", jobIds),
        Sql.array("text", stepNames),
        Sql.array("text", states),
        Sql.array("integer", attempts),
        Sql.array("uuid", nodeIds),
        Sql.array("text", details));
  }

  /** Records one change, see {@link #record(Sql, List)}. */
  void record(Sql db, Change change) throws SQLException {
    record(db, List.of(change));
  }

  /**
   * Reads the recorded changes of a job in a read-only transaction.
   *
   * @param jobId the job id
   * @return its changes, oldest first; empty if none were recorded (or they were deleted)
   */
  public List<JobHistoryEntry> findHistory(UUID jobId) {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_HISTORY_SQL,
                row ->
                    new JobHistoryEntry(
                        row.instant(1),
                        row.string(2),
                        row.string(3),
                        row.integer(4),
                        row.uuid(5),
                        row.string(6)),
                jobId),
        e ->
            new BikeschedException("Reading the history of bikesched job " + jobId + " failed", e));
  }

  /**
   * Deletes up to {@code limit} entries older than the retention period, oldest first.
   *
   * @param after only entries created at or after this time, {@code null} for all
   * @param cutoff only entries created before this time, {@code null} for {@code retention} ago
   * @param retention how long entries are kept
   * @param limit the maximum number of entries to delete
   * @return the deleted batch
   */
  public DeletedBatch deleteHistory(
      @Nullable Instant after, @Nullable Instant cutoff, Duration retention, int limit) {
    return access.withConnection(
        db ->
            db.one(
                    DELETE_HISTORY_SQL,
                    DeletedBatch.MAPPER,
                    Sql.timestamp(cutoff),
                    Sql.seconds(retention),
                    Sql.timestamp(after),
                    limit)
                .orElseThrow(),
        e -> new BikeschedException("Deleting old bikesched job history failed", e));
  }
}
