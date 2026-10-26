// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.bikesched.BikeschedException;
import org.bikesched.admin.HistoryPage;
import org.bikesched.admin.HistoryPosition;
import org.bikesched.admin.HistoryQuery;
import org.bikesched.admin.HistorySubject;
import org.bikesched.history.Actor;
import org.bikesched.history.ActorType;
import org.bikesched.history.EventAction;
import org.bikesched.history.EventEntry;
import org.bikesched.history.HistoryAction;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobAction;
import org.bikesched.history.JobEntry;
import org.bikesched.history.NodeAction;
import org.bikesched.history.NodeEntry;
import org.bikesched.history.StepAction;
import org.bikesched.history.StepEntry;
import org.bikesched.history.SubjectType;
import org.bikesched.internal.database.DatabaseAccess.SqlExceptionHandler;
import org.bikesched.internal.util.UuidUtil;
import org.bikesched.job.JobState;
import org.bikesched.job.StepState;
import org.jspecify.annotations.Nullable;

/**
 * The {@code bikesched_history} table: one row per change of a job, of one of its steps, of an
 * event or of a node.
 *
 * <p>The other stores {@link #record record} their changes through this store, on the {@link Sql}
 * of the transaction making the change, so an entry commits or rolls back with it. With the history
 * disabled, recording does nothing and {@link #change} runs changes without an explicit
 * transaction, exactly as without this store. Thread-safe.
 */
public final class HistoryStore {

  private final DatabaseAccess access;
  private final boolean enabled;

  /** The node writing the entries, once it registered; {@code null} until then. */
  private volatile @Nullable UUID nodeId;

  HistoryStore(DatabaseAccess access, boolean enabled) {
    this.access = access;
    this.enabled = enabled;
  }

  // One statement for any number of changes, in the given order. data is built here from the given
  // specifics and the names of the rows the transaction just wrote (joined by primary key), so the
  // callers pass a name only for a row that is gone. Step entries and job entries without an
  // attempt count take the job's from bikesched_jobs. node_id is the change's own writer (a node's
  // entries about itself) or else the store's registered node.
  private static final String RECORD_SQL =
      """
            insert into bikesched_history (subject_id, id, step_id, subject_type, action, state, node_id,
                                           caused_by_id, attempt, actor_type, actor_name, data)
            select t.subject_id, t.id, t.step_id, t.subject_type, t.action, t.state,
                   coalesce(t.node_id, ?::uuid),
                   t.caused_by_id,
                   case when t.subject_type = 'JOB' then coalesce(t.attempt, j.attempts, 0) end,
                   t.actor_type,
                   t.actor_name,
                   nullif(jsonb_strip_nulls(jsonb_build_object(
                       'subjectName', coalesce(t.subject_name, j.name, e.name, n.name),
                       'stepName', coalesce(s.name, t.step_name),
                       'error', left(t.error, 4000),
                       'message', left(t.message, 4000),
                       'reason', t.reason,
                       'run', t.run,
                       'checks', t.checks,
                       'dueAt', t.due_at,
                       'waitUntil', t.wait_until,
                       'recurrence', t.recurrence,
                       'previousRecurrence', t.previous_recurrence,
                       'idempotencyKey', t.idempotency_key,
                       'reference', t.reference,
                       'childJobId', t.child_job_id,
                       'childState', t.child_state,
                       'eventId', t.event_id,
                       'eventName', t.event_name,
                       'eventKey', t.event_key,
                       'key', e.key,
                       'retention', t.retention,
                       'received', t.received)), '{}'::jsonb)
              from unnest(?::uuid[], ?::uuid[], ?::uuid[], ?::text[], ?::text[], ?::text[], ?::uuid[],
                          ?::integer[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[],
                          ?::integer[], ?::integer[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[],
                          ?::text[], ?::uuid[], ?::text[], ?::uuid[], ?::text[], ?::text[], ?::uuid[],
                          ?::text[], ?::integer[], ?::text[])
                   with ordinality as t (subject_id, id, step_id, subject_type, action, state, caused_by_id,
                                         attempt, actor_type, subject_name, step_name, error, message, reason,
                                         run, checks, due_at, wait_until, recurrence, previous_recurrence,
                                         idempotency_key, reference, child_job_id, child_state, event_id,
                                         event_name, event_key, node_id, retention, received, actor_name,
                                         position)
              left join bikesched_jobs j on t.subject_type = 'JOB' and j.id = t.subject_id
              left join bikesched_events e on t.subject_type = 'EVENT' and e.id = t.subject_id
              left join bikesched_nodes n on t.subject_type = 'NODE' and n.id = t.subject_id
              left join bikesched_steps s on s.id = t.step_id
             order by t.position""";

  private static final String ENTRY_COLUMNS =
      """
            subject_id, id, step_id, subject_type, action, state, node_id, caused_by_id, created_at,
            attempt, actor_type, actor_name, data::text as data""";

  // An event's entries and the entries it caused (the steps that received it, the jobs it resumed).
  private static final String FIND_CAUSED_HISTORY_SQL =
      """
            select %s from (
                select * from bikesched_history where subject_id = ? and subject_type = ?
                union all
                select * from bikesched_history where caused_by_id = ?) h
             order by created_at, id"""
          .formatted(ENTRY_COLUMNS);

  private static final String FIND_JOB_HISTORY_SQL =
      """
            select %s
              from bikesched_history where subject_id = ? and subject_type = 'JOB'
             order by created_at, id"""
          .formatted(ENTRY_COLUMNS);

  // A batch of entries past the retention, oldest first from the given created_at on (see
  // DeletedBatch). Skips locked rows, so concurrent clean-ups on several nodes delete different
  // entries.
  private static final String DELETE_HISTORY_SQL =
      """
            with cutoff as (select coalesce(?, now() - make_interval(secs => ?)) as at),
            batch as (
                select subject_id, id from bikesched_history
                 where created_at >= coalesce(?, '-infinity'::timestamptz)
                   and created_at < (select at from cutoff)
                 order by created_at
                 limit ? for update skip locked),
            deleted as (
                delete from bikesched_history h using batch b
                 where h.subject_id = b.subject_id and h.id = b.id
                returning h.created_at)
            select count(*) as count, max(created_at) as last, (select at from cutoff) as cutoff
              from deleted""";

  /**
   * A change to be recorded: its subject, action, resulting state and actor, plus the specifics
   * that go into the entry's {@code data} (each set with its own method, all optional). Built and
   * recorded by one thread.
   */
  public static final class Change {

    private final SubjectType subjectType;
    private final UUID subjectId;
    private final @Nullable UUID stepId;
    private final HistoryAction action;
    private final @Nullable String state;
    private final @Nullable Integer attempt;
    private ActorType actorType;
    private @Nullable String actorName;
    private @Nullable UUID causedById;
    private @Nullable String subjectName;
    private @Nullable String stepName;
    private @Nullable String error;
    private @Nullable String message;
    private @Nullable String reason;
    private @Nullable Integer run;
    private @Nullable Integer checks;
    private @Nullable Instant dueAt;
    private @Nullable Instant waitUntil;
    private @Nullable String recurrence;
    private @Nullable String previousRecurrence;
    private @Nullable String idempotencyKey;
    private @Nullable String reference;
    private @Nullable UUID childJobId;
    private @Nullable String childState;
    private @Nullable UUID eventId;
    private @Nullable String eventName;
    private @Nullable String eventKey;
    private @Nullable UUID writer;
    private @Nullable Duration retention;
    private @Nullable Integer received;

    private Change(
        SubjectType subjectType,
        UUID subjectId,
        @Nullable UUID stepId,
        HistoryAction action,
        @Nullable String state,
        @Nullable Integer attempt,
        ActorType actorType) {
      this.subjectType = subjectType;
      this.subjectId = subjectId;
      this.stepId = stepId;
      this.action = action;
      this.state = state;
      this.attempt = attempt;
      this.actorType = actorType;
    }

    /**
     * A change of a job itself.
     *
     * @param jobId the job
     * @param action what happened
     * @param state the job's state afterwards
     * @param attempt the job's attempt count afterwards; {@code null} to read it from the job
     * @param actorType who made the change
     */
    public static Change job(
        UUID jobId,
        JobAction action,
        JobState state,
        @Nullable Integer attempt,
        ActorType actorType) {
      return new Change(SubjectType.JOB, jobId, null, action, state.name(), attempt, actorType);
    }

    /**
     * A change of a step; the job's attempt count is read from the job.
     *
     * @param jobId the step's job
     * @param stepId the step
     * @param action what happened
     * @param state the step's state afterwards
     * @param actorType who made the change
     */
    public static Change step(
        UUID jobId, UUID stepId, StepAction action, StepState state, ActorType actorType) {
      return new Change(SubjectType.JOB, jobId, stepId, action, state.name(), null, actorType);
    }

    /**
     * An event published.
     *
     * @param eventId the event
     * @param actorType who published it
     */
    public static Change published(UUID eventId, ActorType actorType) {
      return new Change(
          SubjectType.EVENT, eventId, null, EventAction.PUBLISHED, null, null, actorType);
    }

    /**
     * A change of a node, written by the node itself unless {@link #writtenBy} names another one.
     *
     * @param nodeId the node
     * @param action what happened
     */
    public static Change node(UUID nodeId, NodeAction action) {
      return new Change(SubjectType.NODE, nodeId, null, action, null, null, ActorType.NODE)
          .writtenBy(nodeId);
    }

    /**
     * The user who made the change, replacing the code path's actor type; {@code null} for none.
     */
    public Change actor(@Nullable Actor actor) {
      if (actor != null) {
        this.actorType = actor.type();
        this.actorName = actor.name();
      }
      return this;
    }

    /** The node writing the entry, if not the store's registered node. */
    public Change writtenBy(@Nullable UUID writer) {
      this.writer = writer;
      return this;
    }

    /** How long a published event is kept for steps starting to wait later. */
    public Change retention(@Nullable Duration retention) {
      this.retention = retention;
      return this;
    }

    /** The number of waiting steps that received a published event. */
    public Change received(@Nullable Integer received) {
      this.received = received;
      return this;
    }

    /** The event, job or node that caused the change. */
    public Change causedBy(@Nullable UUID causedById) {
      this.causedById = causedById;
      return this;
    }

    /** The subject's name, for a subject whose row is gone (deleted in the transaction). */
    public Change subjectName(@Nullable String subjectName) {
      this.subjectName = subjectName;
      return this;
    }

    /** The step a job entry is about (e.g. where it suspended); step entries have their own. */
    public Change stepName(@Nullable String stepName) {
      this.stepName = stepName;
      return this;
    }

    /** The error of a failure; cut to 4000 characters. */
    public Change error(@Nullable String error) {
      this.error = error;
      return this;
    }

    /** A message, e.g. of a cancellation; cut to 4000 characters. */
    public Change message(@Nullable String message) {
      this.message = message;
      return this;
    }

    /** Why it happened, one of the constants documented with the action. */
    public Change reason(@Nullable String reason) {
      this.reason = reason;
      return this;
    }

    /** The number of the run: a compensation attempt, a failure handler run, a step's attempt. */
    public Change run(@Nullable Integer run) {
      this.run = run;
      return this;
    }

    /** The number of checks of a poll. */
    public Change checks(@Nullable Integer checks) {
      this.checks = checks;
      return this;
    }

    /** When the job is due (again). */
    public Change dueAt(@Nullable Instant dueAt) {
      this.dueAt = dueAt;
      return this;
    }

    /** Until when a step waits (or a sleep's wake-up time). */
    public Change waitUntil(@Nullable Instant waitUntil) {
      this.waitUntil = waitUntil;
      return this;
    }

    /** The recurrence of an occurrence, and the one it was moved from. */
    public Change recurrence(@Nullable String recurrence, @Nullable String previousRecurrence) {
      this.recurrence = recurrence;
      this.previousRecurrence = previousRecurrence;
      return this;
    }

    /** The idempotency key and the reference a new job was scheduled with. */
    public Change keys(@Nullable String idempotencyKey, @Nullable String reference) {
      this.idempotencyKey = idempotencyKey;
      this.reference = reference;
      return this;
    }

    /** The child job a step started, and the state it ended in. */
    public Change child(@Nullable UUID childJobId, @Nullable String childState) {
      this.childJobId = childJobId;
      this.childState = childState;
      return this;
    }

    /** The event a step published. */
    public Change eventId(@Nullable UUID eventId) {
      this.eventId = eventId;
      return this;
    }

    /** The event a step waits for. */
    public Change event(@Nullable String eventName, @Nullable String eventKey) {
      this.eventName = eventName;
      this.eventKey = eventKey;
      return this;
    }
  }

  /** Whether changes are recorded. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Sets the node that writes the entries from now on, once it registered.
   *
   * @param nodeId the node's id
   */
  public void writtenBy(UUID nodeId) {
    this.nodeId = nodeId;
  }

  /**
   * Runs a statement (or several) changing jobs, steps, events or nodes: in a transaction if the
   * history is enabled, so the recorded entries commit with the change, otherwise on a plain
   * connection.
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
    var subjectIds = new UUID[size];
    var ids = new UUID[size];
    var stepIds = new @Nullable UUID[size];
    var subjectTypes = new String[size];
    var actions = new String[size];
    var states = new @Nullable String[size];
    var causedByIds = new @Nullable UUID[size];
    var attempts = new @Nullable Integer[size];
    var actorTypes = new String[size];
    var subjectNames = new @Nullable String[size];
    var stepNames = new @Nullable String[size];
    var errors = new @Nullable String[size];
    var messages = new @Nullable String[size];
    var reasons = new @Nullable String[size];
    var runs = new @Nullable Integer[size];
    var checks = new @Nullable Integer[size];
    var dueAts = new @Nullable String[size];
    var waitUntils = new @Nullable String[size];
    var recurrences = new @Nullable String[size];
    var previousRecurrences = new @Nullable String[size];
    var idempotencyKeys = new @Nullable String[size];
    var references = new @Nullable String[size];
    var childJobIds = new @Nullable UUID[size];
    var childStates = new @Nullable String[size];
    var eventIds = new @Nullable UUID[size];
    var eventNames = new @Nullable String[size];
    var eventKeys = new @Nullable String[size];
    var writers = new @Nullable UUID[size];
    var retentions = new @Nullable String[size];
    var received = new @Nullable Integer[size];
    var actorNames = new @Nullable String[size];
    for (int i = 0; i < size; i++) {
      Change change = changes.get(i);
      subjectIds[i] = change.subjectId;
      ids[i] = UuidUtil.timeOrdered();
      stepIds[i] = change.stepId;
      subjectTypes[i] = change.subjectType.name();
      actions[i] = change.action.name();
      states[i] = change.state;
      causedByIds[i] = change.causedById;
      attempts[i] = change.attempt;
      actorTypes[i] = change.actorType.name();
      subjectNames[i] = change.subjectName;
      stepNames[i] = change.stepName;
      errors[i] = change.error;
      messages[i] = change.message;
      reasons[i] = change.reason;
      runs[i] = change.run;
      checks[i] = change.checks;
      dueAts[i] = text(change.dueAt);
      waitUntils[i] = text(change.waitUntil);
      recurrences[i] = change.recurrence;
      previousRecurrences[i] = change.previousRecurrence;
      idempotencyKeys[i] = change.idempotencyKey;
      references[i] = change.reference;
      childJobIds[i] = change.childJobId;
      childStates[i] = change.childState;
      eventIds[i] = change.eventId;
      eventNames[i] = change.eventName;
      eventKeys[i] = change.eventKey;
      writers[i] = change.writer;
      Duration retention = change.retention;
      retentions[i] = retention == null ? null : retention.toString();
      received[i] = change.received;
      actorNames[i] = change.actorName;
    }
    db.update(
        RECORD_SQL,
        Sql.typed(nodeId, Types.OTHER),
        Sql.array("uuid", subjectIds),
        Sql.array("uuid", ids),
        Sql.array("uuid", stepIds),
        Sql.array("text", subjectTypes),
        Sql.array("text", actions),
        Sql.array("text", states),
        Sql.array("uuid", causedByIds),
        Sql.array("integer", attempts),
        Sql.array("text", actorTypes),
        Sql.array("text", subjectNames),
        Sql.array("text", stepNames),
        Sql.array("text", errors),
        Sql.array("text", messages),
        Sql.array("text", reasons),
        Sql.array("integer", runs),
        Sql.array("integer", checks),
        Sql.array("text", dueAts),
        Sql.array("text", waitUntils),
        Sql.array("text", recurrences),
        Sql.array("text", previousRecurrences),
        Sql.array("text", idempotencyKeys),
        Sql.array("text", references),
        Sql.array("uuid", childJobIds),
        Sql.array("text", childStates),
        Sql.array("uuid", eventIds),
        Sql.array("text", eventNames),
        Sql.array("text", eventKeys),
        Sql.array("uuid", writers),
        Sql.array("text", retentions),
        Sql.array("integer", received),
        Sql.array("text", actorNames));
  }

  /** Records one change, see {@link #record(Sql, List)}. */
  void record(Sql db, Change change) throws SQLException {
    record(db, List.of(change));
  }

  /** An instant as ISO-8601 text in UTC (e.g. {@code 2026-10-26T08:30:58.207Z}), as stored. */
  private static @Nullable String text(@Nullable Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /**
   * Reads the recorded changes of a job and its steps in a read-only transaction.
   *
   * @param jobId the job id
   * @return its job and step entries, oldest first; empty if none were recorded (or they were
   *     deleted)
   */
  public List<HistoryEntry> findJobHistory(UUID jobId) {
    return access.inReadOnlyTransaction(
        db -> db.list(FIND_JOB_HISTORY_SQL, HistoryStore::entry, jobId),
        e ->
            new BikeschedException("Reading the history of bikesched job " + jobId + " failed", e));
  }

  /**
   * Reads the entries of an event or node and the entries it caused, in a read-only transaction.
   *
   * @param subjectType {@code EVENT} or {@code NODE}
   * @param subjectId the event or node id
   * @return its entries and the caused ones, oldest first; empty if none were recorded (or they
   *     were deleted)
   */
  public List<HistoryEntry> findHistoryWithCaused(SubjectType subjectType, UUID subjectId) {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_CAUSED_HISTORY_SQL,
                HistoryStore::entry,
                subjectId,
                subjectType.name(),
                subjectId),
        e ->
            new BikeschedException(
                "Reading the history of bikesched "
                    + subjectType.name().toLowerCase(Locale.ROOT)
                    + " "
                    + subjectId
                    + " failed",
                e));
  }

  /**
   * Reads a page of entries of every subject, newest first, in a read-only transaction.
   *
   * <p>The time bounds, the position, the cause and the users' actor type select the entries to
   * scan, along the indexes; the other filters apply to at most {@code scanLimit} scanned entries,
   * so a rare action costs a bounded scan per page, not one through the whole table. A page that
   * reached the bound without filling up ends at the last scanned entry.
   *
   * @param query the filters, the page size and the position
   * @param scanLimit the maximum number of entries scanned for the filters applied after the scan
   * @return the page with the nodes that wrote its entries and the subjects that caused them
   */
  public HistoryPage findHistory(HistoryQuery query, int scanLimit) {
    List<String> scan = new ArrayList<>();
    List<@Nullable Object> parameters = new ArrayList<>();
    Instant since = query.since();
    if (since != null) {
      scan.add("created_at >= ?");
      parameters.add(Sql.timestamp(since));
    }
    Instant until = query.until();
    if (until != null) {
      scan.add("created_at < ?");
      parameters.add(Sql.timestamp(until));
    }
    HistoryPosition after = query.after();
    if (after != null) {
      // Not as a row comparison, which the index on created_at alone cannot serve.
      scan.add("created_at <= ? and (created_at < ? or id < ?)");
      parameters.add(Sql.timestamp(after.createdAt()));
      parameters.add(Sql.timestamp(after.createdAt()));
      parameters.add(after.id());
    }
    UUID causedById = query.causedById();
    if (causedById != null) {
      scan.add("caused_by_id = ?");
      parameters.add(causedById);
    }
    ActorType actorType = query.actorType();
    if (actorType == ActorType.USER) {
      // A literal, so the planner can use the partial index of the users' entries.
      scan.add("actor_type = 'USER'");
    }
    List<String> filter = new ArrayList<>();
    List<@Nullable Object> filterParameters = new ArrayList<>();
    if (actorType != null && actorType != ActorType.USER) {
      filter.add("actor_type = ?");
      filterParameters.add(actorType.name());
    }
    Class<? extends HistoryEntry> entryType = query.entryType();
    if (entryType != null) {
      filter.add(KIND + " = ?");
      filterParameters.add(kind(entryType));
    }
    if (!query.actions().isEmpty()) {
      filter.add(KIND + " || ':' || action = any(?::text[])");
      filterParameters.add(
          Sql.array(
              "text",
              query.actions().stream().map(a -> kind(a) + ":" + a.name()).toArray(String[]::new)));
    }
    int limit = query.limit();
    int scanned = filter.isEmpty() ? limit + 1 : Math.max(scanLimit, limit + 1);
    parameters.add(scanned);
    parameters.addAll(filterParameters);
    parameters.add(limit + 1);
    parameters.add(scanned);
    String sql =
        FIND_HISTORY_SQL.formatted(
            scan.isEmpty() ? "true" : String.join(" and ", scan),
            filter.isEmpty() ? "true" : String.join(" and ", filter));
    return access.inReadOnlyTransaction(
        db -> {
          List<PageRow> rows = db.list(sql, PageRow::of, parameters.toArray());
          List<HistoryEntry> entries = new ArrayList<>(rows.size());
          HistoryPosition next = null;
          for (PageRow row : rows) {
            if (row.lastScanned()) {
              next = HistoryPosition.of(row.entry());
            } else {
              entries.add(row.entry());
            }
          }
          if (entries.size() > limit) {
            entries = entries.subList(0, limit);
            next = HistoryPosition.of(entries.getLast());
          }
          UUID[] ids =
              entries.stream()
                  .flatMap(entry -> Stream.of(entry.nodeId(), entry.causedById()))
                  .filter(Objects::nonNull)
                  .distinct()
                  .toArray(UUID[]::new);
          Map<UUID, HistorySubject> subjects = new HashMap<>();
          if (ids.length > 0) {
            for (HistorySubject subject :
                db.list(SUBJECTS_SQL, HistoryStore::subject, Sql.array("uuid", ids))) {
              subjects.put(subject.id(), subject);
            }
          }
          return new HistoryPage(entries, next, subjects);
        },
        e -> new BikeschedException("Reading the bikesched history failed", e));
  }

  // The type of an entry as one value: JOB, STEP, EVENT or NODE.
  private static final String KIND =
      "(case when step_id is null then subject_type else 'STEP' end)";

  // The matching entries among the scanned ones, newest first, one more than the page holds; and
  // the last scanned entry if the scan reached its bound, where the next page starts if this one
  // does not fill up. The scan is materialized, so it runs once for both.
  private static final String FIND_HISTORY_SQL =
      """
            with scanned as materialized (
                select * from bikesched_history
                 where %%s
                 order by created_at desc, id desc
                 limit ?),
            page as (
                select * from scanned where %%s order by created_at desc, id desc limit ?)
            select %1$s, false as last_scanned from page
            union all
            select %1$s, true as last_scanned
              from (select * from scanned order by created_at, id limit 1) l
             where (select count(*) from scanned) = ?
             order by last_scanned, created_at desc, id desc"""
          .formatted(ENTRY_COLUMNS);

  // The type and name of each subject: a registered node's, or else from the subject's latest entry
  // (one step backwards along the primary key; every entry names its subject).
  private static final String SUBJECTS_SQL =
      """
            select s.id, coalesce(h.subject_type, 'NODE') as subject_type,
                   coalesce(n.name, h.data->>'subjectName') as name
              from unnest(?::uuid[]) as s(id)
              left join bikesched_nodes n on n.id = s.id
              left join lateral (
                  select subject_type, data from bikesched_history
                   where subject_id = s.id
                   order by id desc
                   limit 1) h on true
             where n.id is not null or h.subject_type is not null""";

  private record PageRow(HistoryEntry entry, boolean lastScanned) {
    static PageRow of(Sql.Row row) throws SQLException {
      return new PageRow(HistoryStore.entry(row), row.bool("last_scanned"));
    }
  }

  private static HistorySubject subject(Sql.Row row) throws SQLException {
    return new HistorySubject(
        row.uuid("id"), row.enumValue("subject_type", SubjectType.class), row.string("name"));
  }

  private static String kind(Class<? extends HistoryEntry> entryType) {
    if (entryType == JobEntry.class) {
      return "JOB";
    } else if (entryType == StepEntry.class) {
      return "STEP";
    } else if (entryType == EventEntry.class) {
      return "EVENT";
    } else if (entryType == NodeEntry.class) {
      return "NODE";
    }
    throw new IllegalArgumentException("Not a type of history entry: " + entryType.getName());
  }

  private static String kind(HistoryAction action) {
    return switch (action) {
      case JobAction ignored -> "JOB";
      case StepAction ignored -> "STEP";
      case EventAction ignored -> "EVENT";
      case NodeAction ignored -> "NODE";
    };
  }

  /** Maps a row of {@link #ENTRY_COLUMNS} to the entry of its subject type. */
  static HistoryEntry entry(Sql.Row row) throws SQLException {
    UUID id = row.uuid("id");
    UUID subjectId = row.uuid("subject_id");
    Instant createdAt = row.instant("created_at");
    String action = row.string("action");
    ActorType actorType = row.enumValue("actor_type", ActorType.class);
    String actorName = row.string("actor_name");
    UUID nodeId = row.uuid("node_id");
    UUID causedById = row.uuid("caused_by_id");
    String data = row.string("data");
    UUID stepId = row.uuid("step_id");
    return switch (row.enumValue("subject_type", SubjectType.class)) {
      case JOB ->
          stepId == null
              ? new JobEntry(
                  id,
                  subjectId,
                  createdAt,
                  JobAction.valueOf(action),
                  row.enumValue("state", JobState.class),
                  row.integer("attempt"),
                  actorType,
                  actorName,
                  nodeId,
                  causedById,
                  data)
              : new StepEntry(
                  id,
                  subjectId,
                  stepId,
                  createdAt,
                  StepAction.valueOf(action),
                  row.enumValue("state", StepState.class),
                  row.integer("attempt"),
                  actorType,
                  actorName,
                  nodeId,
                  causedById,
                  data);
      case EVENT ->
          new EventEntry(
              id,
              subjectId,
              createdAt,
              EventAction.valueOf(action),
              actorType,
              actorName,
              nodeId,
              causedById,
              data);
      case NODE ->
          new NodeEntry(
              id,
              subjectId,
              createdAt,
              NodeAction.valueOf(action),
              actorType,
              actorName,
              nodeId,
              causedById,
              data);
    };
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
        e -> new BikeschedException("Deleting old bikesched history failed", e));
  }
}
