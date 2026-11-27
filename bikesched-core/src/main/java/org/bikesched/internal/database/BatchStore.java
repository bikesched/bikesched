// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.history.Actor;
import org.bikesched.history.ActorType;
import org.bikesched.history.JobAction;
import org.bikesched.history.StepAction;
import org.bikesched.internal.database.HistoryStore.Change;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.internal.util.ValidationUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.StepState;
import org.jspecify.annotations.Nullable;

/**
 * Batches of child jobs started as one step of their parent: a {@code CHILD_JOB_BATCH} step with a
 * {@code CHILD_JOB} step per item ({@code batch_step_id}), the items' jobs ({@code HELD} until the
 * batch's parallelism admits them), the end of an item, waiting for and timing out a batch, and
 * cancelling the held items of a batch whose parent ends. Used by {@link JobStore} and {@link
 * StepStore} in their own transactions and on its own.
 *
 * <p>Every transaction touching a batch takes its locks in one order, so no two wait for each other
 * in a cycle: the ending item's own job row, the batch step, the item steps in id order, other item
 * rows (only {@code for update skip locked}, never waiting), the parent's row.
 */
public final class BatchStore {

  /** Items inserted (and their steps restarted) per statement. */
  static final int CHUNK_SIZE = 1_000;

  private static final String FIND_STEP_SQL =
      """
            select id, type, state from bikesched_steps where job_id = ? and name = ? for update""";

  private static final String FIND_STARTED_SQL =
      """
            select id, type, state from bikesched_steps where job_id = ? and name = ?""";

  private static final String BATCH_COLUMNS =
      """
            id, name, job_id, state, batch_item_count,
            coalesce(batch_parallelism, 0) as batch_parallelism, batch_active_count, batch_ended_count,
            batch_fail_fast, compensable, wake_at, wake_at <= now() as deadline_passed
              from bikesched_steps""";

  private static final String LOCK_BATCH_SQL =
      "select " + BATCH_COLUMNS + " where id = ? and type = 'CHILD_JOB_BATCH' for update";

  private static final String FIND_BATCH_SQL =
      "select " + BATCH_COLUMNS + " where job_id = ? and name = ? and type = 'CHILD_JOB_BATCH'";

  private static final String INSERT_BATCH_STEP_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, batch_item_count,
                                         batch_parallelism, batch_active_count, batch_ended_count,
                                         batch_fail_fast, compensable)
            values (?, ?, ?, 'CHILD_JOB_BATCH', 'WAITING', 1, ?, ?, ?, 0, ?, ?)""";

  // A compensated batch starts again when its parent is retried, with new children; it keeps its
  // parallelism, mode and compensation.
  private static final String RESTART_BATCH_STEP_SQL =
      """
            update bikesched_steps
               set state = 'WAITING', batch_active_count = ?, batch_ended_count = 0, wake_at = null,
                   last_error = null,
                   attempts = attempts + 1, started_at = now(), finished_at = null
             where id = ? and state = 'COMPENSATED'""";

  // The first `limit` items are due, the others HELD until admitted (keeping the due time asked
  // for, see ADMIT_SQL).
  private static final String INSERT_ITEMS_SQL =
      """
            insert into bikesched_jobs (id, name, type, state, payload, scheduled_at, priority, max_attempts,
                                        timeout_millis, parent_job_id, reference, batch_step_id, batch_index,
                                        batch_key)
            select t.id, t.name, 'CHILD', case when t.idx < ? then 'SCHEDULED' else 'HELD' end,
                   t.payload::jsonb, ?, ?, ?, ?, ?, ?, ?, t.idx, t.key
              from unnest(?::uuid[], ?::text[], ?::text[], ?::integer[], ?::text[])
                       as t (id, name, payload, idx, key)""";

  // Item steps of held items are not compensable until admitted: a held item that is cancelled
  // never ran.
  private static final String INSERT_ITEM_STEPS_SQL =
      """
            insert into bikesched_steps (id, job_id, name, type, state, attempts, child_job_id, child_job_name,
                                         compensable, batch_step_id)
            select t.id, ?, t.name, 'CHILD_JOB', 'WAITING', 1, t.child, t.child_name, ? and t.idx < ?, ?
              from unnest(?::uuid[], ?::text[], ?::uuid[], ?::text[], ?::integer[])
                       as t (id, name, child, child_name, idx)""";

  private static final String RESTART_ITEM_STEPS_SQL =
      """
            update bikesched_steps s
               set state = 'WAITING', child_job_id = t.child, child_job_name = t.child_name, child_end_state = null,
                   result = null, last_error = null, wake_at = null, compensable = ? and t.idx < ?,
                   attempts = s.attempts + 1, started_at = now(), finished_at = null
              from unnest(?::text[], ?::uuid[], ?::text[], ?::integer[]) as t (name, child, child_name, idx)
             where s.batch_step_id = ? and s.name = t.name""";

  private static final String FIND_ITEMS_SQL =
      """
            select id, name, state, child_job_id, child_job_name, child_end_state, result::text as result,
                   last_error, compensable
              from bikesched_steps where batch_step_id = ? order by id""";

  // The step of an item that ended, if the item is still its batch's child (a compensated batch
  // restarted since has new children): by its name, along the steps' unique (job_id, name), not by
  // scanning the batch's item steps.
  private static final String LOCK_ITEM_STEP_SQL =
      """
            select s.id, s.name, s.state
              from bikesched_jobs c
              join bikesched_steps s on s.job_id = c.parent_job_id and s.name = ? || '/' || c.batch_key
             where c.id = ? and s.batch_step_id = ? and s.child_job_id = c.id
               for update of s""";

  private static final String COMPLETE_ITEM_STEP_SQL =
      """
            update bikesched_steps s
               set state = 'SUCCEEDED', child_end_state = c.state,
                   result = case when c.state = 'SUCCEEDED' then c.result end,
                   last_error = case when c.state = 'SUCCEEDED' then null else c.last_error end,
                   finished_at = now()
              from bikesched_jobs c
             where s.id = ? and c.id = s.child_job_id and s.state = 'WAITING'
            returning c.state as child_state, c.last_error""";

  private static final String COUNT_ENDED_SQL =
      """
            update bikesched_steps
               set batch_ended_count = batch_ended_count + ?, batch_active_count = batch_active_count - ?
             where id = ?
            returning batch_ended_count, batch_active_count""";

  private static final String COUNT_CANCELLED_SQL =
      "update bikesched_steps set batch_ended_count = batch_ended_count + ? where id = ?";

  // Admits the first held items in item order; a held item being cancelled right now is skipped
  // (the next end admits another one). Keeps a due time in the future the batch was scheduled for.
  private static final String ADMIT_SQL =
      """
            update bikesched_jobs j
               set state = 'SCHEDULED', scheduled_at = greatest(j.scheduled_at, now()), updated_at = now()
              from (select id from bikesched_jobs
                     where batch_step_id = ? and state = 'HELD'
                     order by batch_index
                     limit ?
                       for update skip locked) h
             where j.id = h.id
            returning j.id, j.name, j.attempts, j.scheduled_at""";

  private static final String MAKE_COMPENSABLE_SQL =
      """
            update bikesched_steps set compensable = true
             where batch_step_id = ? and child_job_id = any(?::uuid[])""";

  private static final String COUNT_ADMITTED_SQL =
      "update bikesched_steps set batch_active_count = batch_active_count + ? where id = ?";

  private static final String END_BATCH_SQL =
      """
            update bikesched_steps set state = ?, last_error = ?, finished_at = now()
             where id = ? and state = 'WAITING'""";

  // The unfinished item steps of a batch that ended early (failed fast, timed out, cancelled with
  // its parent), in id order: later ends of their items no longer change them.
  private static final String END_ITEM_STEPS_SQL =
      """
            update bikesched_steps s set state = ?, last_error = ?, finished_at = now()
              from (select id from bikesched_steps
                     where batch_step_id = ? and state = 'WAITING'
                     order by id
                       for update) w
             where s.id = w.id
            returning s.id""";

  // Held items never ran: they end CANCELLED right away, skipping one being admitted or cancelled
  // concurrently (it ends on its own).
  private static final String CANCEL_HELD_SQL =
      """
            update bikesched_jobs j
               set state = 'CANCELLED', last_error = ?, finished_at = now(), updated_at = now()
              from (select id from bikesched_jobs
                     where batch_step_id = ? and state = 'HELD'
                     order by batch_index
                       for update skip locked) h
             where j.id = h.id
            returning j.id, j.attempts""";

  // The steps of held items cancelled with their batch's parent: their items never ran, and the
  // batch's later outcome reads them as cancelled.
  private static final String CANCEL_HELD_ITEM_STEPS_SQL =
      """
            update bikesched_steps set state = 'CANCELLED', last_error = ?, finished_at = now()
             where batch_step_id = ? and type = 'CHILD_JOB' and state = 'WAITING'
               and child_job_id = any(?::uuid[])""";

  // A restarted batch's held items of before its compensation (its parent compensated or ended
  // without cancelling them, e.g. written by an older version): nobody waits for them.
  private static final String CANCEL_OLD_HELD_SQL =
      """
            update bikesched_jobs
               set state = 'CANCELLED', last_error = ?, finished_at = now(), updated_at = now()
             where batch_step_id = ? and state = 'HELD'
            returning id, attempts""";

  // Fail-fast: the unfinished siblings are cancelled like Bikesched.cancel does; one locked right
  // now (ending, being claimed, writing a step) is skipped here and asked after the commit, see
  // REQUEST_CANCEL_REMAINING_SQL.
  private static final String REQUEST_CANCEL_ITEMS_SQL =
      """
            update bikesched_jobs j
               set cancel_requested_at = coalesce(j.cancel_requested_at, now()), cancel_message = ?,
                   scheduled_at = case when j.state in ('SCHEDULED', 'WAITING') then now() else j.scheduled_at end,
                   updated_at = now()
              from (select c.id from bikesched_jobs c
                     where c.id in (select child_job_id from bikesched_steps where batch_step_id = ?)
                       and c.batch_step_id = ?
                       and c.state in ('SCHEDULED', 'WAITING', 'RUNNING') and c.compensation_end_state is null
                       and c.failure_end_state is null
                     order by c.batch_index
                       for update skip locked) s
             where j.id = s.id
            returning j.id, j.state, j.attempts, j.name""";

  // Fail-fast, after the commit: the siblings the first pass skipped, now waiting for their locks
  // in item order (no batch step is held, so no cycle), and only those not asked yet.
  private static final String REQUEST_CANCEL_REMAINING_SQL =
      """
            update bikesched_jobs j
               set cancel_requested_at = now(), cancel_message = ?,
                   scheduled_at = case when j.state in ('SCHEDULED', 'WAITING') then now() else j.scheduled_at end,
                   updated_at = now()
              from (select c.id from bikesched_jobs c
                     where c.id in (select child_job_id from bikesched_steps where batch_step_id = ?)
                       and c.batch_step_id = ?
                       and c.state in ('SCHEDULED', 'WAITING', 'RUNNING') and c.compensation_end_state is null
                       and c.failure_end_state is null and c.cancel_requested_at is null
                     order by c.batch_index
                       for update) s
             where j.id = s.id and j.cancel_requested_at is null
            returning j.id, j.state, j.attempts, j.name""";

  private static final String SET_DEADLINE_SQL =
      """
            update bikesched_steps set wake_at = now() + ? * interval '1 millisecond'
             where id = ? and state = 'WAITING'
            returning wake_at""";

  // A WAITING parent is due again; a RUNNING one finds the batch ended when it waits.
  private static final String RESUME_PARENT_SQL =
      """
            update bikesched_jobs set state = 'SCHEDULED', scheduled_at = now(), updated_at = now()
             where id = ? and state = 'WAITING'
            returning attempts, name""";

  // A held item cancelled via Bikesched.cancel ends right away, it never ran.
  // Along the steps' unique (job_id, name).
  private static final String USED_STEP_NAME_SQL =
      "select name from bikesched_steps where job_id = ? and name = any(?::text[]) limit 1";

  private static final String FIND_HELD_ITEM_SQL =
      "select batch_step_id from bikesched_jobs where id = ? and state = 'HELD'";

  private static final String CANCEL_HELD_ITEM_SQL =
      """
            update bikesched_jobs
               set state = 'CANCELLED', cancel_requested_at = now(), cancel_message = ?, last_error = ?,
                   finished_at = now(), updated_at = now()
             where id = ? and state = 'HELD'
            returning batch_step_id, attempts""";

  private static final String LOCK_WAITING_BATCHES_SQL =
      """
            select id, name from bikesched_steps
             where job_id = ? and type = 'CHILD_JOB_BATCH' and state = 'WAITING'
             order by id
               for update""";

  private final DatabaseAccess access;
  private final HistoryStore history;

  BatchStore(DatabaseAccess access, HistoryStore history) {
    this.access = access;
    this.history = history;
  }

  /**
   * An item of a new batch.
   *
   * @param jobName the item's job name
   * @param key its key, unique within the batch
   * @param payload its payload as JSON text, {@code null} if none
   */
  public record NewItem(String jobName, String key, @Nullable String payload) {}

  /**
   * A new batch, with the settings of every item.
   *
   * @param items the items, in order
   * @param scheduledAt the earliest time the items may run
   * @param priority their priority
   * @param maxAttempts their maximum number of attempts
   * @param timeout their attempt timeout, {@code null} for the executing node's default
   * @param reference their reference, {@code null} for none
   * @param parallelism the items active at once, {@code null} for all
   * @param failFast whether the batch ends at the first item that does not succeed
   * @param compensable whether the items' steps have a compensation
   */
  public record NewBatch(
      List<NewItem> items,
      Instant scheduledAt,
      int priority,
      int maxAttempts,
      @Nullable Duration timeout,
      @Nullable String reference,
      @Nullable Integer parallelism,
      boolean failFast,
      boolean compensable) {

    /** Copies the items. */
    public NewBatch {
      items = List.copyOf(items);
    }

    /** The number of items admitted right away. */
    int admitted() {
      return parallelism == null ? items.size() : Math.min(parallelism, items.size());
    }
  }

  /**
   * An item of a stored batch.
   *
   * @param key its key
   * @param jobId its child job
   * @param jobName the child's job name
   */
  public record ItemRef(String key, UUID jobId, String jobName) {}

  /**
   * A batch started by {@link #startBatch}.
   *
   * @param step the batch step
   * @param items its items, in order
   * @param started whether it was started now ({@code false}: by an earlier run of its parent)
   */
  public record StartedBatch(StepRef step, List<ItemRef> items, boolean started) {

    /** Copies the items. */
    public StartedBatch {
      items = List.copyOf(items);
    }
  }

  /**
   * An item of a batch with its outcome, as its step stores it.
   *
   * @param step the item's step
   * @param item the item
   * @param state the item step's state: {@code SUCCEEDED} once the item ended (its {@code
   *     childState} holds the outcome), {@code TIMED_OUT} or {@code CANCELLED} if the batch ended
   *     before it, {@code WAITING} while it runs
   * @param childState the state the item ended in, {@code null} unless the step {@code SUCCEEDED}
   * @param result the item's result as JSON text, may be {@code null}
   * @param error the item's error or cancel message, or why its batch ended before it
   * @param compensable whether the item's step has a compensation to run
   */
  public record ItemOutcome(
      StepRef step,
      ItemRef item,
      StepState state,
      @Nullable JobState childState,
      @Nullable String result,
      @Nullable String error,
      boolean compensable) {}

  /** A parent's wait for its batch, by the state of the batch step. */
  public sealed interface BatchWait {

    /** The batch step. */
    StepRef step();

    /**
     * The batch ended: every item ended ({@code SUCCEEDED}), it failed fast ({@code FAILED}), timed
     * out or was cancelled.
     *
     * @param state the batch step's state
     * @param items every item with its outcome, in order
     */
    record Ended(StepRef step, StepState state, List<ItemOutcome> items) implements BatchWait {

      /** Copies the items. */
      public Ended {
        items = List.copyOf(items);
      }
    }

    /**
     * The batch's items run.
     *
     * @param waitUntil the deadline of the wait, {@code null} if none
     */
    record Waiting(StepRef step, @Nullable Instant waitUntil) implements BatchWait {}

    /**
     * The batch step neither waits nor ended: compensated (only met while compensating).
     *
     * @param state its state
     */
    record NotWaiting(StepRef step, StepState state) implements BatchWait {}
  }

  /** A row of {@code LOCK_BATCH_SQL} or {@code FIND_BATCH_SQL}. */
  private record BatchRow(
      UUID id,
      String name,
      UUID jobId,
      StepState state,
      int itemCount,
      int parallelism,
      int activeCount,
      int endedCount,
      boolean failFast,
      boolean compensable,
      @Nullable Instant wakeAt,
      boolean deadlinePassed) {

    static BatchRow from(Sql.Row row) throws SQLException {
      return new BatchRow(
          row.uuid("id"),
          row.string("name"),
          row.uuid("job_id"),
          row.enumValue("state", StepState.class),
          row.integer("batch_item_count"),
          row.integer("batch_parallelism"),
          row.integer("batch_active_count"),
          row.integer("batch_ended_count"),
          row.bool("batch_fail_fast"),
          row.bool("compensable"),
          row.instant("wake_at"),
          row.bool("deadline_passed"));
    }

    StepRef step() {
      return new StepRef(id, name);
    }

    String described() {
      return "batch '" + name + "'";
    }
  }

  /**
   * Starts a batch of child jobs as a step of its parent, exactly once: inserts the batch step, the
   * items (the first {@code parallelism} due, the others {@code HELD}) and their steps in one
   * transaction, fenced by the parent's lock. If the step exists, returns its items without
   * scheduling; a {@code COMPENSATED} batch (its parent was retried after its compensation) starts
   * new children for the same keys.
   *
   * @param parentJobId the parent
   * @param lock the lock of the parent's claim (node and token)
   * @param stepName the batch's step name
   * @param batch the items and their settings
   * @return the batch, and whether it was started now
   * @throws IllegalStateException if the step name belongs to another type of step, or a
   *     compensated batch is restarted with other keys
   * @throws JobLockLostException if the parent is no longer running on the node
   */
  public StartedBatch startBatch(UUID parentJobId, JobLock lock, String stepName, NewBatch batch) {
    return access.inTransaction(
        db -> {
          // The batch step before the parent's row (the lock order of a batch)
          Optional<FoundStep> found =
              db.one(
                  FIND_STEP_SQL,
                  row ->
                      new FoundStep(
                          row.uuid("id"),
                          row.enumValue("type", StepType.class),
                          row.enumValue("state", StepState.class)),
                  parentJobId,
                  stepName);
          StepStore.requireJobLock(db, parentJobId, lock);
          if (found.isPresent()) {
            StepType.CHILD_JOB_BATCH.require(stepName, parentJobId, found.get().type());
            StepRef step = new StepRef(found.get().id(), stepName);
            List<ItemRef> stored = items(db, step);
            if (found.get().state() != StepState.COMPENSATED) {
              return new StartedBatch(step, stored, false);
            }
            return restart(db, parentJobId, step, stored, batch);
          }
          return insert(db, parentJobId, stepName, batch);
        },
        e ->
            new BikeschedException(
                "Starting job batch '" + stepName + "' of bikesched job " + parentJobId + " failed",
                e));
  }

  /**
   * Reads a batch its parent started before, without locking it: a run of the parent after the
   * first returns it without checking or mapping its items again.
   *
   * @param parentJobId the parent
   * @param stepName the batch's step name
   * @return the started batch, empty if it has not started or was compensated (and starts again)
   * @throws IllegalStateException if the step is no batch
   */
  public Optional<StartedBatch> findStarted(UUID parentJobId, String stepName) {
    return access.inReadOnlyTransaction(
        db -> {
          Optional<FoundStep> found =
              db.one(
                  FIND_STARTED_SQL,
                  row ->
                      new FoundStep(
                          row.uuid("id"),
                          row.enumValue("type", StepType.class),
                          row.enumValue("state", StepState.class)),
                  parentJobId,
                  stepName);
          if (found.isEmpty() || found.get().state() == StepState.COMPENSATED) {
            return Optional.<StartedBatch>empty();
          }
          StepType.CHILD_JOB_BATCH.require(stepName, parentJobId, found.get().type());
          StepRef step = new StepRef(found.get().id(), stepName);
          return Optional.of(new StartedBatch(step, items(db, step), false));
        },
        e ->
            new BikeschedException(
                "Reading job batch '" + stepName + "' of bikesched job " + parentJobId + " failed",
                e));
  }

  /** A row of {@code FIND_STEP_SQL}. */
  private record FoundStep(UUID id, StepType type, StepState state) {}

  private StartedBatch insert(Sql db, UUID parentJobId, String stepName, NewBatch batch)
      throws SQLException {
    requireFreeItemNames(db, parentJobId, stepName, batch);
    UUID stepId = UuidUtil.timeOrdered();
    int admitted = batch.admitted();
    db.update(
        INSERT_BATCH_STEP_SQL,
        stepId,
        parentJobId,
        stepName,
        batch.items().size(),
        Sql.typed(batch.parallelism(), Types.INTEGER),
        admitted,
        batch.failFast(),
        batch.compensable());
    List<ItemRef> items = newItems(batch);
    insertItems(db, parentJobId, stepId, batch, items);
    for (int from = 0; from < items.size(); from += CHUNK_SIZE) {
      int to = Math.min(items.size(), from + CHUNK_SIZE);
      int size = to - from;
      var ids = new UUID[size];
      var names = new String[size];
      var children = new UUID[size];
      var childNames = new String[size];
      var indexes = new Integer[size];
      for (int i = 0; i < size; i++) {
        ItemRef item = items.get(from + i);
        ids[i] = UuidUtil.timeOrdered(); // monotonic: the id order is the item order
        names[i] = itemStepName(stepName, item.key());
        children[i] = item.jobId();
        childNames[i] = item.jobName();
        indexes[i] = from + i;
      }
      db.update(
          INSERT_ITEM_STEPS_SQL,
          parentJobId,
          batch.compensable(),
          admitted,
          stepId,
          Sql.array("uuid", ids),
          Sql.array("text", names),
          Sql.array("uuid", children),
          Sql.array("text", childNames),
          Sql.array("integer", indexes));
    }
    recordStarted(db, parentJobId, stepId, batch, items);
    if (items.isEmpty()) {
      endEmpty(db, parentJobId, stepId);
    }
    return new StartedBatch(new StepRef(stepId, stepName), items, true);
  }

  private StartedBatch restart(
      Sql db, UUID parentJobId, StepRef step, List<ItemRef> stored, NewBatch batch)
      throws SQLException {
    List<String> storedKeys = stored.stream().map(ItemRef::key).toList();
    List<String> keys = batch.items().stream().map(NewItem::key).toList();
    if (!storedKeys.equals(keys)) {
      Set<String> missing = new LinkedHashSet<>(storedKeys);
      keys.forEach(missing::remove);
      Set<String> added = new LinkedHashSet<>(keys);
      storedKeys.forEach(added::remove);
      throw new IllegalStateException(
          "Job batch '"
              + step.name()
              + "' of job "
              + parentJobId
              + " was compensated and starts again with its keys in their order; "
              + (missing.isEmpty() && added.isEmpty()
                  ? "the keys are in another order"
                  : "missing " + missing + ", new " + added));
    }
    BatchRow row = db.one(LOCK_BATCH_SQL, BatchRow::from, step.id()).orElseThrow();
    String compensated = row.described() + " was compensated";
    List<CancelledItem> old =
        db.list(
            CANCEL_OLD_HELD_SQL,
            r -> new CancelledItem(r.uuid("id"), r.integer("attempts")),
            compensated,
            step.id());
    if (history.isEnabled() && !old.isEmpty()) {
      List<Change> changes = new ArrayList<>(old.size());
      for (CancelledItem item : old) {
        changes.add(
            Change.job(
                    item.id(),
                    JobAction.CANCELLED,
                    JobState.CANCELLED,
                    item.attempts(),
                    ActorType.NODE)
                .causedBy(parentJobId)
                .message(compensated));
      }
      history.record(db, changes);
    }
    // The stored batch wins: its parallelism, mode and compensation.
    NewBatch restarted =
        new NewBatch(
            batch.items(),
            batch.scheduledAt(),
            batch.priority(),
            batch.maxAttempts(),
            batch.timeout(),
            batch.reference(),
            row.parallelism() == 0 ? null : row.parallelism(),
            row.failFast(),
            row.compensable());
    int admitted = restarted.admitted();
    db.update(RESTART_BATCH_STEP_SQL, admitted, step.id());
    List<ItemRef> items = newItems(restarted);
    insertItems(db, parentJobId, step.id(), restarted, items);
    for (int from = 0; from < items.size(); from += CHUNK_SIZE) {
      int to = Math.min(items.size(), from + CHUNK_SIZE);
      int size = to - from;
      var names = new String[size];
      var children = new UUID[size];
      var childNames = new String[size];
      var indexes = new Integer[size];
      for (int i = 0; i < size; i++) {
        ItemRef item = items.get(from + i);
        names[i] = itemStepName(step.name(), item.key());
        children[i] = item.jobId();
        childNames[i] = item.jobName();
        indexes[i] = from + i;
      }
      db.update(
          RESTART_ITEM_STEPS_SQL,
          restarted.compensable(),
          admitted,
          Sql.array("text", names),
          Sql.array("uuid", children),
          Sql.array("text", childNames),
          Sql.array("integer", indexes),
          step.id());
    }
    recordStarted(db, parentJobId, step.id(), restarted, items);
    if (items.isEmpty()) {
      endEmpty(db, parentJobId, step.id());
    }
    return new StartedBatch(step, items, true);
  }

  /** An empty batch has ended once it started: nothing to wait for. */
  private void endEmpty(Sql db, UUID parentJobId, UUID stepId) throws SQLException {
    db.update(END_BATCH_SQL, StepState.SUCCEEDED.name(), null, stepId);
    history.record(
        db,
        Change.step(
            parentJobId, stepId, StepAction.BATCH_ENDED, StepState.SUCCEEDED, ActorType.JOB));
  }

  private static List<ItemRef> newItems(NewBatch batch) {
    List<ItemRef> items = new ArrayList<>(batch.items().size());
    for (NewItem item : batch.items()) {
      items.add(new ItemRef(item.key(), UuidUtil.timeOrdered(), item.jobName()));
    }
    return items;
  }

  private static void insertItems(
      Sql db, UUID parentJobId, UUID stepId, NewBatch batch, List<ItemRef> items)
      throws SQLException {
    int admitted = batch.admitted();
    for (int from = 0; from < items.size(); from += CHUNK_SIZE) {
      int to = Math.min(items.size(), from + CHUNK_SIZE);
      int size = to - from;
      var ids = new UUID[size];
      var names = new String[size];
      var payloads = new @Nullable String[size];
      var indexes = new Integer[size];
      var keys = new String[size];
      for (int i = 0; i < size; i++) {
        ItemRef item = items.get(from + i);
        ids[i] = item.jobId();
        names[i] = item.jobName();
        payloads[i] = batch.items().get(from + i).payload();
        indexes[i] = from + i;
        keys[i] = item.key();
      }
      db.update(
          INSERT_ITEMS_SQL,
          admitted,
          Sql.timestamp(batch.scheduledAt()),
          batch.priority(),
          batch.maxAttempts(),
          Sql.millis(batch.timeout()),
          parentJobId,
          batch.reference(),
          stepId,
          Sql.array("uuid", ids),
          Sql.array("text", names),
          Sql.array("text", payloads),
          Sql.array("integer", indexes),
          Sql.array("text", keys));
    }
    for (int i = 0; i < admitted; i++) {
      db.due(items.get(i).jobName(), batch.scheduledAt());
    }
  }

  private void recordStarted(
      Sql db, UUID parentJobId, UUID stepId, NewBatch batch, List<ItemRef> items)
      throws SQLException {
    if (!history.isEnabled()) {
      return;
    }
    List<Change> changes = new ArrayList<>(items.size() + 1);
    changes.add(
        Change.step(parentJobId, stepId, StepAction.BATCH_STARTED, StepState.WAITING, ActorType.JOB)
            .batch(items.size(), batch.parallelism(), batch.failFast()));
    int admitted = batch.admitted();
    for (int i = 0; i < items.size(); i++) {
      boolean held = i >= admitted;
      changes.add(
          Change.job(
                  items.get(i).jobId(),
                  held ? JobAction.HELD : JobAction.SCHEDULED,
                  held ? JobState.HELD : JobState.SCHEDULED,
                  0,
                  ActorType.JOB)
              .causedBy(parentJobId)
              .dueAt(held ? null : batch.scheduledAt())
              .keys(null, batch.reference()));
    }
    // in chunks like the items, so no statement binds arrays of a whole raised batch
    for (int from = 0; from < changes.size(); from += CHUNK_SIZE) {
      history.record(db, changes.subList(from, Math.min(changes.size(), from + CHUNK_SIZE)));
    }
  }

  /** Rejects a batch whose item step names the job already uses for other steps. */
  private static void requireFreeItemNames(
      Sql db, UUID parentJobId, String stepName, NewBatch batch) throws SQLException {
    for (int from = 0; from < batch.items().size(); from += CHUNK_SIZE) {
      String[] names =
          batch.items().subList(from, Math.min(batch.items().size(), from + CHUNK_SIZE)).stream()
              .map(item -> itemStepName(stepName, item.key()))
              .toArray(String[]::new);
      Optional<String> taken =
          db.one(
              USED_STEP_NAME_SQL, row -> row.string("name"), parentJobId, Sql.array("text", names));
      if (taken.isPresent()) {
        throw new IllegalStateException(
            "Job batch '"
                + stepName
                + "' of job "
                + parentJobId
                + " would name an item step '"
                + taken.get()
                + "', the name of another step: step names must be unique within a job");
      }
    }
  }

  /** The step name of an item: the batch's step name, a slash and the item's key. */
  public static String itemStepName(String stepName, String key) {
    return stepName + "/" + key;
  }

  private static List<ItemRef> items(Sql db, StepRef batch) throws SQLException {
    return outcomes(db, batch).stream().map(ItemOutcome::item).toList();
  }

  private static List<ItemOutcome> outcomes(Sql db, StepRef batch) throws SQLException {
    int prefix = batch.name().length() + 1;
    return db.list(
        FIND_ITEMS_SQL,
        row ->
            new ItemOutcome(
                new StepRef(row.uuid("id"), row.string("name")),
                new ItemRef(
                    row.string("name").substring(prefix),
                    row.uuid("child_job_id"),
                    row.string("child_job_name")),
                row.enumValue("state", StepState.class),
                row.enumValue("child_end_state", JobState.class),
                row.string("result"),
                row.string("last_error"),
                row.bool("compensable")),
        batch.id());
  }

  /**
   * Waits for a batch: returns every item's outcome if the batch ended; otherwise stores the
   * deadline on the first wait with a timeout (the stored one wins on later waits), times the batch
   * out once it has passed, or reports it waiting. Locks the batch step before the parent's fence.
   *
   * @param jobId the parent
   * @param lock the lock of the parent's claim (node and token)
   * @param step the batch step
   * @param timeout how long to wait at most, counted from the first wait; {@code null} for no limit
   * @return the wait
   * @throws JobLockLostException if the parent is no longer running on the node
   */
  public BatchWait awaitBatch(UUID jobId, JobLock lock, StepRef step, @Nullable Duration timeout) {
    return access.inTransaction(
        db -> {
          BatchRow batch =
              db.one(LOCK_BATCH_SQL, BatchRow::from, step.id())
                  .orElseThrow(() -> new IllegalStateException("No job batch " + step));
          StepStore.requireJobLock(db, jobId, lock);
          switch (batch.state()) {
            case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED -> {
              return new BatchWait.Ended(step, batch.state(), outcomes(db, step));
            }
            case WAITING -> {}
            default -> {
              return new BatchWait.NotWaiting(step, batch.state());
            }
          }
          if (batch.deadlinePassed()) {
            timeOut(db, batch);
            return new BatchWait.Ended(step, StepState.TIMED_OUT, outcomes(db, step));
          }
          if (timeout != null && batch.wakeAt() == null) {
            Instant deadline =
                db.one(
                        SET_DEADLINE_SQL,
                        row -> row.instant("wake_at"),
                        Sql.millis(timeout),
                        step.id())
                    .orElseThrow();
            history.record(
                db,
                Change.step(jobId, step.id(), StepAction.WAITING, StepState.WAITING, ActorType.JOB)
                    .waitUntil(deadline));
            return new BatchWait.Waiting(step, deadline);
          }
          return new BatchWait.Waiting(step, batch.wakeAt());
        },
        e ->
            new BikeschedException(
                "Waiting for job batch '" + step.name() + "' of bikesched job " + jobId + " failed",
                e));
  }

  /**
   * Reads a batch without changing it (to replay its parent while compensating).
   *
   * @param jobId the parent
   * @param stepName the batch's step name
   * @return the batch's state and items, empty if it has not started
   */
  public Optional<BatchWait> findBatch(UUID jobId, String stepName) {
    return access.inReadOnlyTransaction(
        db -> {
          Optional<BatchRow> batch = db.one(FIND_BATCH_SQL, BatchRow::from, jobId, stepName);
          if (batch.isEmpty()) {
            return Optional.<BatchWait>empty();
          }
          StepRef step = batch.get().step();
          List<ItemOutcome> items = outcomes(db, step);
          return Optional.<BatchWait>of(
              switch (batch.get().state()) {
                case WAITING -> new BatchWait.Waiting(step, batch.get().wakeAt());
                case SUCCEEDED, FAILED, TIMED_OUT, CANCELLED ->
                    new BatchWait.Ended(step, batch.get().state(), items);
                default -> new BatchWait.NotWaiting(step, batch.get().state());
              });
        },
        e ->
            new BikeschedException(
                "Reading job batch '" + stepName + "' of bikesched job " + jobId + " failed", e));
  }

  /**
   * Reads the items of a batch with their outcomes, without changing them.
   *
   * @param step the batch step
   * @return the items, in order
   */
  public List<ItemOutcome> findItems(StepRef step) {
    return access.inReadOnlyTransaction(
        db -> outcomes(db, step),
        e -> new BikeschedException("Reading job batch '" + step.name() + "' failed", e));
  }

  /** Times a batch out: its unfinished item steps too, and its held items are cancelled. */
  private void timeOut(Sql db, BatchRow batch) throws SQLException {
    List<Change> changes = new ArrayList<>();
    db.update(END_BATCH_SQL, StepState.TIMED_OUT.name(), null, batch.id());
    changes.add(
        Change.step(
            batch.jobId(), batch.id(), StepAction.TIMED_OUT, StepState.TIMED_OUT, ActorType.NODE));
    endItemSteps(db, batch, StepState.TIMED_OUT, StepAction.TIMED_OUT, null, changes);
    cancelHeld(db, batch, batch.described() + " timed out", batch.jobId(), changes);
    history.record(db, changes);
  }

  private void endItemSteps(
      Sql db,
      BatchRow batch,
      StepState state,
      StepAction action,
      @Nullable String message,
      List<Change> changes)
      throws SQLException {
    List<UUID> ended =
        db.list(
            END_ITEM_STEPS_SQL,
            row -> row.uuid("id"),
            state.name(),
            ValidationUtil.truncateMessage(message),
            batch.id());
    if (history.isEnabled()) {
      for (UUID step : ended) {
        changes.add(Change.step(batch.jobId(), step, action, state, ActorType.NODE));
      }
    }
  }

  /** Cancels the held items of a batch, counting them as ended. */
  private void cancelHeld(
      Sql db, BatchRow batch, String message, UUID causedBy, List<Change> changes)
      throws SQLException {
    List<CancelledItem> cancelled =
        db.list(
            CANCEL_HELD_SQL,
            row -> new CancelledItem(row.uuid("id"), row.integer("attempts")),
            ValidationUtil.truncateMessage(message),
            batch.id());
    if (cancelled.isEmpty()) {
      return;
    }
    db.update(COUNT_CANCELLED_SQL, cancelled.size(), batch.id());
    db.update(
        CANCEL_HELD_ITEM_STEPS_SQL,
        ValidationUtil.truncateMessage(message),
        batch.id(),
        Sql.array("uuid", cancelled.stream().map(CancelledItem::id).toArray()));
    if (history.isEnabled()) {
      for (CancelledItem item : cancelled) {
        changes.add(
            Change.job(
                    item.id(),
                    JobAction.CANCELLED,
                    JobState.CANCELLED,
                    item.attempts(),
                    ActorType.NODE)
                .causedBy(causedBy)
                .message(message));
      }
    }
  }

  /** A row of {@code CANCEL_HELD_SQL}. */
  private record CancelledItem(UUID id, int attempts) {}

  /**
   * After an item ended in a terminal state in the caller's transaction: completes its step with
   * its outcome, counts it, admits held items as the parallelism allows, and ends the batch (and
   * resumes its parent) once every item ended, or a fail-fast batch at the first item that did not
   * succeed.
   *
   * @param itemId the item that ended
   * @param batchStepId its batch step
   * @param wasActive whether the item was admitted ({@code false} for a held item cancelled)
   */
  void itemEnded(Sql db, UUID itemId, UUID batchStepId, boolean wasActive) throws SQLException {
    Optional<BatchRow> locked = db.one(LOCK_BATCH_SQL, BatchRow::from, batchStepId);
    if (locked.isEmpty()) {
      return; // the parent was deleted
    }
    BatchRow batch = locked.get();
    Optional<StepRef> itemStep =
        db.one(
            LOCK_ITEM_STEP_SQL,
            row -> new StepRef(row.uuid("id"), row.string("name")),
            batch.name(),
            itemId,
            batchStepId);
    if (itemStep.isEmpty()) {
      return; // a child of the batch before it was compensated and restarted
    }
    List<Change> changes = new ArrayList<>();
    Optional<EndedItem> ended =
        db.one(
            COMPLETE_ITEM_STEP_SQL,
            row ->
                new EndedItem(
                    row.enumValue("child_state", JobState.class), row.string("last_error")),
            itemStep.get().id());
    if (ended.isPresent()) {
      changes.add(
          Change.step(
                  batch.jobId(),
                  itemStep.get().id(),
                  StepAction.CHILD_ENDED,
                  StepState.SUCCEEDED,
                  ActorType.NODE)
              .causedBy(itemId)
              .child(null, ended.get().state().name()));
    }
    Counts counts =
        db.one(
                COUNT_ENDED_SQL,
                row ->
                    new Counts(row.integer("batch_ended_count"), row.integer("batch_active_count")),
                1,
                wasActive ? 1 : 0,
                batchStepId)
            .orElseThrow();
    if (batch.state() == StepState.WAITING && ended.isPresent()) {
      String key = itemStep.get().name().substring(batch.name().length() + 1);
      if (batch.failFast() && ended.get().state() != JobState.SUCCEEDED) {
        failFast(db, batch, key, itemId, ended.get(), changes);
      } else {
        if (wasActive) {
          admit(db, batch, counts.active(), itemId, changes);
        }
        if (counts.ended() >= batch.itemCount()) {
          db.update(END_BATCH_SQL, StepState.SUCCEEDED.name(), null, batchStepId);
          changes.add(
              Change.step(
                      batch.jobId(),
                      batchStepId,
                      StepAction.BATCH_ENDED,
                      StepState.SUCCEEDED,
                      ActorType.NODE)
                  .causedBy(itemId));
          resumeParent(db, batch.jobId(), itemId, changes);
        }
      }
    }
    history.record(db, changes);
  }

  /** A row of {@code COMPLETE_ITEM_STEP_SQL}. */
  private record EndedItem(JobState state, @Nullable String error) {}

  /** A row of {@code COUNT_ENDED_SQL}. */
  private record Counts(int ended, int active) {}

  private void admit(Sql db, BatchRow batch, int active, UUID causedBy, List<Change> changes)
      throws SQLException {
    if (batch.parallelism() == 0 || active >= batch.parallelism()) {
      return;
    }
    List<AdmittedItem> admitted =
        db.list(
            ADMIT_SQL,
            row ->
                new AdmittedItem(
                    row.uuid("id"),
                    row.string("name"),
                    row.integer("attempts"),
                    row.instant("scheduled_at")),
            batch.id(),
            batch.parallelism() - active);
    if (admitted.isEmpty()) {
      return;
    }
    db.update(COUNT_ADMITTED_SQL, admitted.size(), batch.id());
    if (batch.compensable()) {
      db.update(
          MAKE_COMPENSABLE_SQL,
          batch.id(),
          Sql.array("uuid", admitted.stream().map(AdmittedItem::id).toArray(UUID[]::new)));
    }
    for (AdmittedItem item : admitted) {
      db.due(item.name(), item.scheduledAt());
      changes.add(
          Change.job(
                  item.id(),
                  JobAction.ADMITTED,
                  JobState.SCHEDULED,
                  item.attempts(),
                  ActorType.NODE)
              .causedBy(causedBy)
              .dueAt(item.scheduledAt()));
    }
  }

  /** A row of {@code ADMIT_SQL}. */
  private record AdmittedItem(UUID id, String name, int attempts, Instant scheduledAt) {}

  private void failFast(
      Sql db, BatchRow batch, String key, UUID itemId, EndedItem item, List<Change> changes)
      throws SQLException {
    String error = item.error();
    String outcome =
        switch (item.state()) {
          case CANCELLED -> "was cancelled" + (error == null ? "" : ": " + error);
          case COMPENSATION_FAILED -> "failed compensating" + (error == null ? "" : ": " + error);
          default -> "failed" + (error == null ? "" : ": " + error);
        };
    db.update(
        END_BATCH_SQL,
        StepState.FAILED.name(),
        ValidationUtil.truncateMessage("item '" + key + "' " + outcome),
        batch.id());
    changes.add(
        Change.step(
                batch.jobId(), batch.id(), StepAction.BATCH_ENDED, StepState.FAILED, ActorType.NODE)
            .causedBy(itemId)
            .failedFast(key));
    String message = batch.described() + " failed fast at item '" + key + "'";
    endItemSteps(db, batch, StepState.CANCELLED, StepAction.CANCELLED, message, changes);
    cancelHeld(db, batch, message, itemId, changes);
    List<CancelRequested> requested =
        db.list(
            REQUEST_CANCEL_ITEMS_SQL,
            row ->
                new CancelRequested(
                    row.uuid("id"),
                    row.enumValue("state", JobState.class),
                    row.integer("attempts"),
                    row.string("name")),
            ValidationUtil.truncateMessage(message),
            batch.id(),
            batch.id());
    requested(db, requested, itemId, message, changes);
    // Siblings locked right now (writing a step, ending an attempt, being claimed) were skipped:
    // asked once this transaction committed, without the batch step's lock.
    UUID batchStepId = batch.id();
    db.afterCommit(
        next -> {
          List<Change> later = new ArrayList<>();
          requested(
              next,
              next.list(
                  REQUEST_CANCEL_REMAINING_SQL,
                  row ->
                      new CancelRequested(
                          row.uuid("id"),
                          row.enumValue("state", JobState.class),
                          row.integer("attempts"),
                          row.string("name")),
                  ValidationUtil.truncateMessage(message),
                  batchStepId,
                  batchStepId),
              itemId,
              message,
              later);
          history.record(next, later);
          return null;
        });
    resumeParent(db, batch.jobId(), itemId, changes);
  }

  /** Reports and records the siblings a fail-fast end asked to cancel. */
  private static void requested(
      Sql db, List<CancelRequested> requested, UUID itemId, String message, List<Change> changes) {
    for (CancelRequested sibling : requested) {
      if (sibling.state() != JobState.RUNNING) {
        db.dueNow(sibling.name());
      }
      changes.add(
          Change.job(
                  sibling.id(),
                  JobAction.CANCEL_REQUESTED,
                  sibling.state(),
                  sibling.attempts(),
                  ActorType.NODE)
              .causedBy(itemId)
              .message(message));
    }
  }

  /** A row of {@code REQUEST_CANCEL_ITEMS_SQL}. */
  private record CancelRequested(UUID id, JobState state, int attempts, String name) {}

  private static void resumeParent(Sql db, UUID parentJobId, UUID causedBy, List<Change> changes)
      throws SQLException {
    Optional<ResumedParent> resumed =
        db.one(
            RESUME_PARENT_SQL,
            row -> new ResumedParent(row.integer("attempts"), row.string("name")),
            parentJobId);
    if (resumed.isPresent()) {
      db.dueNow(resumed.get().name());
      changes.add(
          Change.job(
                  parentJobId,
                  JobAction.RESUMED,
                  JobState.SCHEDULED,
                  resumed.get().attempts(),
                  ActorType.NODE)
              .causedBy(causedBy));
    }
  }

  /** A row of {@code RESUME_PARENT_SQL}. */
  private record ResumedParent(int attempts, String name) {}

  /**
   * Cancels a held item via {@code Bikesched.cancel}: it ends {@code CANCELLED} right away (it
   * never ran), counted as ended by its batch without admitting another one.
   *
   * @param jobId the item
   * @param message why it is cancelled, may be {@code null}
   * @param actor the user cancelling it, {@code null} for the application
   * @return whether the job was a held item
   */
  boolean cancelHeldItem(UUID jobId, @Nullable String message, @Nullable Actor actor) {
    return access.inTransaction(
        db -> {
          // Its batch step first: held rows are only locked under it, so an admission or the
          // batch's end never skips one (and strands it) while this transaction runs.
          Optional<UUID> batchStepId =
              db.one(FIND_HELD_ITEM_SQL, row -> row.uuid("batch_step_id"), jobId);
          if (batchStepId.isEmpty()) {
            return false;
          }
          db.one(LOCK_BATCH_SQL, BatchRow::from, batchStepId.get()); // gone with a deleted parent
          String truncated = ValidationUtil.truncateMessage(message);
          Optional<HeldItem> held =
              db.one(
                  CANCEL_HELD_ITEM_SQL,
                  row -> new HeldItem(row.uuid("batch_step_id"), row.integer("attempts")),
                  truncated,
                  truncated,
                  jobId);
          if (held.isEmpty()) {
            return false;
          }
          history.record(
              db,
              Change.job(
                      jobId,
                      JobAction.CANCELLED,
                      JobState.CANCELLED,
                      held.get().attempts(),
                      ActorType.APPLICATION)
                  .actor(actor)
                  .message(message));
          itemEnded(db, jobId, held.get().batchStepId(), false);
          return true;
        },
        e -> new BikeschedException("Cancelling bikesched job " + jobId + " failed", e));
  }

  /** A row of {@code CANCEL_HELD_ITEM_SQL}. */
  private record HeldItem(UUID batchStepId, int attempts) {}

  /**
   * Locks the {@code WAITING} batches of a job, in id order, before its own row: first in each
   * transaction of the job that may end it or its batches.
   *
   * @return the locked batch steps
   */
  static List<StepRef> lockWaitingBatches(Sql db, UUID jobId) throws SQLException {
    return db.list(
        LOCK_WAITING_BATCHES_SQL, row -> new StepRef(row.uuid("id"), row.string("name")), jobId);
  }

  /**
   * After the parent of the given batches was cancelled or ended in the caller's transaction:
   * cancels their held items, which nobody would admit anymore.
   *
   * @param jobId the parent
   * @param batches its batches locked by {@link #lockWaitingBatches} (some may have ended since)
   */
  void cancelHeldItems(Sql db, UUID jobId, List<StepRef> batches) throws SQLException {
    List<Change> changes = new ArrayList<>();
    for (StepRef step : batches) {
      Optional<BatchRow> batch = db.one(LOCK_BATCH_SQL, BatchRow::from, step.id());
      if (batch.isPresent()) {
        cancelHeld(
            db, batch.get(), batch.get().described() + " ended with job " + jobId, jobId, changes);
      }
    }
    history.record(db, changes);
  }
}
