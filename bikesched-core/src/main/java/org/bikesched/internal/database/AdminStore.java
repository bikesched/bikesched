// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.admin.EventNameSummary;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobData;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.JobTypeSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.RecurringJobRuns;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.job.JobState;

/**
 * Read-only lookups across the tables for {@link org.bikesched.admin.Admin}; every method runs in
 * its own read-only transaction. Payloads and results are only read by {@link #findJobData} and
 * {@link #findEventPayload}.
 */
public final class AdminStore {

  private final DatabaseAccess access;

  AdminStore(DatabaseAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  // The columns of a JobSummary from the jobs in %s; 'infinity' (a wait without a deadline)
  // reads as null.
  private static final String JOB_COLUMNS =
      """
            select j.id, j.type, j.state, j.attempts, j.max_attempts, j.priority,
                   nullif(j.scheduled_at, 'infinity'), j.created_at, j.updated_at, j.finished_at,
                   j.last_error, j.parent_job_id, p.type, j.recurrence, j.idempotency_key, n.name
              from %s j
              left join bikesched_jobs p on p.id = j.parent_job_id
              left join bikesched_nodes n on n.id = j.locked_by""";

  // One page of jobs, newest first, read along bikesched_jobs_created_at_idx (or
  // bikesched_jobs_type_created_at_idx with a type, or the primary key for an id range); parents
  // and nodes are joined to the page only.
  private static final String JOB_PAGE =
      """
            (select id, type, state, attempts, max_attempts, priority, scheduled_at, created_at,
                    updated_at, finished_at, last_error, parent_job_id, recurrence,
                    idempotency_key, locked_by
               from bikesched_jobs%s
              order by created_at desc, id desc
              limit ?)""";

  private static final String FIND_JOB_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs") + "\n where j.id = ?";

  // Along the partial unique index on (type, idempotency_key).
  private static final String FIND_KEYED_JOB_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs") + "\n where j.type = ? and j.idempotency_key = ?";

  // The occurrences of a recurring job: the pending one along bikesched_jobs_recurring_idx, the
  // ended ones as a page along bikesched_jobs_type_created_at_idx.
  private static final String RECURRING_STATES =
      "('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')";

  private static final String FIND_PENDING_RUN_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs")
          + "\n where j.type = ? and j.recurrence is not null and j.state in "
          + RECURRING_STATES;

  private static final String FIND_ENDED_RUNS_SQL =
      JOB_COLUMNS.formatted(
              JOB_PAGE.formatted(
                  "\n              where type = ? and recurrence is not null and state not in "
                      + RECURRING_STATES))
          + "\n order by j.created_at desc, j.id desc";

  private static final String FIND_JOB_DATA_SQL =
      "select payload::text, result::text from bikesched_jobs where id = ?";

  // A child job's state: its current state while it exists, the recorded one after it was deleted.
  private static final String FIND_STEPS_SQL =
      """
            select s.name, s.kind, s.state, s.attempts, s.last_error, s.started_at, s.finished_at,
                   nullif(s.wait_until, 'infinity'), s.event_name, s.event_key, s.child_job_id,
                   s.child_job_type, coalesce(c.state, s.child_state), c.id is not null
              from bikesched_steps s
              left join bikesched_jobs c on c.id = s.child_job_id
             where s.job_id = ?
             order by s.started_at, s.name""";

  private static final String FIND_JOB_TYPES_SQL =
      """
            select type, state, count(*), max(recurrence)
              from bikesched_jobs group by type, state order by type, state""";

  // The columns of an EventSummary: receivers counted via bikesched_steps_event_id_idx; the
  // newest unexpired event of a name and key is the one a new wait receives.
  private static final String EVENT_COLUMNS =
      """
            select e.id, e.name, e.key, e.idempotency_key, e.published_at, e.expires_at,
                   (select count(*) from bikesched_steps s
                     where s.event_id = e.id and s.kind = 'EVENT_WAIT'),
                   e.expires_at > now() and not exists (
                       select 1 from bikesched_events l
                        where l.name = e.name and l.key = e.key and l.expires_at > now()
                          and (l.published_at, l.id) > (e.published_at, e.id))
              from bikesched_events e""";

  // One page of unexpired events, newest first, read along bikesched_events_published_at_idx;
  // the select list is only computed for the rows of the page.
  private static final String EVENT_PAGE =
      """

             where e.expires_at > now()%s
             order by e.published_at desc, e.id desc
             limit ?""";

  private static final String FIND_EVENT_SQL = EVENT_COLUMNS + "\n where e.id = ?";

  private static final String FIND_EVENT_PAYLOAD_SQL =
      "select coalesce(payload::text, 'null') from bikesched_events where id = ?";

  // The waiting steps use bikesched_steps_waiting_idx.
  private static final String FIND_EVENT_NAMES_SQL =
      """
            select name, sum(events)::int, sum(waiting)::int
              from (select name, count(*) as events, 0 as waiting from bikesched_events
                     where expires_at > now() group by name
                    union all
                    select event_name, 0, count(*) from bikesched_steps
                     where kind = 'EVENT_WAIT' and state = 'WAITING' group by event_name) names
             group by name order by name""";

  private static final String FIND_EVENT_STEPS_SQL =
      """
            select s.job_id, j.type, j.state, s.name, s.kind
              from bikesched_steps s
              join bikesched_jobs j on j.id = s.job_id
             where s.event_id = ? and s.kind in ('EVENT_PUBLISH', 'EVENT_WAIT')
             order by s.started_at, s.job_id, s.name""";

  // Uses bikesched_steps_waiting_idx (event_name, event_key).
  private static final String FIND_WAITING_STEPS_SQL =
      """
            select s.job_id, j.type, s.name, s.event_name, s.event_key, s.started_at,
                   nullif(s.wait_until, 'infinity')
              from bikesched_steps s
              join bikesched_jobs j on j.id = s.job_id
             where s.kind = 'EVENT_WAIT' and s.state = 'WAITING' and s.event_name = ?
               and (?::text is null or starts_with(s.event_key, ?::text))
             order by s.started_at, s.job_id
             limit ?""";

  private static final String FIND_NODES_SQL =
      """
            select n.id, n.name, n.started_at, n.last_heartbeat_at,
                   n.last_heartbeat_at >= now() - make_interval(secs => ?),
                   (select count(*) from bikesched_jobs j where j.locked_by = n.id)::int
              from bikesched_nodes n
             %s""";

  public List<JobSummary> findJobs(JobQuery query) {
    Objects.requireNonNull(query, "query");
    // Only the filters in use go into the statement: "? is null or ..." would keep the planner
    // from reading the page along an index.
    List<String> conditions = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    if (query.type() != null) {
      conditions.add("type = ?");
      parameters.add(query.type());
    }
    if (!query.states().isEmpty()) {
      conditions.add("state = any(?)");
      parameters.add(Sql.array("text", query.states().stream().map(Enum::name).toArray()));
    }
    if (query.nodeId() != null) {
      conditions.add("locked_by = ?");
      parameters.add(query.nodeId());
    }
    if (query.createdBefore() != null) {
      conditions.add("(created_at, id) < (?, ?)");
      parameters.add(Sql.timestamp(query.createdBefore()));
      parameters.add(query.beforeId());
    }
    parameters.add(query.limit());
    String sql =
        JOB_COLUMNS.formatted(JOB_PAGE.formatted(where(conditions, "\n              where ")))
            + "\n order by j.created_at desc, j.id desc";
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::jobSummary, parameters.toArray()),
        e -> new BikeschedException("Finding bikesched jobs failed", e));
  }

  public List<JobSummary> findJobsByIdRange(UUID from, UUID to, int limit) {
    String sql =
        JOB_COLUMNS.formatted(JOB_PAGE.formatted("\n              where id between ? and ?"))
            + "\n order by j.created_at desc, j.id desc";
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::jobSummary, from, to, limit),
        e -> new BikeschedException("Finding bikesched jobs by id failed", e));
  }

  public RecurringJobRuns findRecurringJobRuns(String type, int limit) {
    return access.inReadOnlyTransaction(
        db ->
            new RecurringJobRuns(
                type,
                db.list(FIND_PENDING_RUN_SQL, AdminStore::jobSummary, type).stream()
                    .findFirst()
                    .orElse(null),
                db.list(FIND_ENDED_RUNS_SQL, AdminStore::jobSummary, type, limit)),
        e -> new BikeschedException("Finding the runs of recurring job " + type + " failed", e));
  }

  public Optional<JobSummary> findJob(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_JOB_SQL, AdminStore::jobSummary, jobId),
        e -> new BikeschedException("Reading bikesched job " + jobId + " failed", e));
  }

  public Optional<JobSummary> findJob(String type, String idempotencyKey) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_KEYED_JOB_SQL, AdminStore::jobSummary, type, idempotencyKey),
        e -> new BikeschedException("Reading bikesched job by idempotency key failed", e));
  }

  public Optional<JobData> findJobData(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_JOB_DATA_SQL, row -> new JobData(row.string(1), row.string(2)), jobId),
        e -> new BikeschedException("Reading the data of bikesched job " + jobId + " failed", e));
  }

  public List<StepInfo> findSteps(UUID jobId) {
    Objects.requireNonNull(jobId, "jobId");
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_STEPS_SQL,
                row -> {
                  String childState = row.string(13);
                  return new StepInfo(
                      row.string(1),
                      row.string(2),
                      row.string(3),
                      row.integer(4),
                      row.string(5),
                      row.instant(6),
                      row.instant(7),
                      row.instant(8),
                      row.string(9),
                      row.string(10),
                      row.uuid(11),
                      row.string(12),
                      childState == null ? null : JobState.valueOf(childState),
                      row.bool(14));
                },
                jobId),
        e -> new BikeschedException("Reading the steps of bikesched job " + jobId + " failed", e));
  }

  public List<JobTypeSummary> findJobTypes() {
    record Count(String type, JobState state, int count, String recurrence) {}
    List<Count> counts =
        access.inReadOnlyTransaction(
            db ->
                db.list(
                    FIND_JOB_TYPES_SQL,
                    row ->
                        new Count(
                            row.string(1),
                            JobState.valueOf(row.string(2)),
                            row.integer(3),
                            row.string(4))),
            e -> new BikeschedException("Counting bikesched jobs failed", e));
    Map<String, Map<JobState, Integer>> byType = new LinkedHashMap<>();
    Map<String, String> recurrences = new LinkedHashMap<>();
    for (Count count : counts) {
      byType
          .computeIfAbsent(count.type(), type -> new EnumMap<>(JobState.class))
          .put(count.state(), count.count());
      if (count.recurrence() != null) {
        recurrences.putIfAbsent(count.type(), count.recurrence());
      }
    }
    List<JobTypeSummary> types = new ArrayList<>(byType.size());
    byType.forEach(
        (type, byState) ->
            types.add(new JobTypeSummary(type, byState, recurrences.get(type), false)));
    return types;
  }

  public List<EventSummary> findEvents(EventQuery query) {
    Objects.requireNonNull(query, "query");
    List<String> conditions = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    if (query.name() != null) {
      conditions.add("e.name = ?");
      parameters.add(query.name());
    }
    if (query.keyPrefix() != null) {
      conditions.add("starts_with(e.key, ?)");
      parameters.add(query.keyPrefix());
    }
    if (query.publishedBefore() != null) {
      conditions.add("(e.published_at, e.id) < (?, ?)");
      parameters.add(Sql.timestamp(query.publishedBefore()));
      parameters.add(query.beforeId());
    }
    parameters.add(query.limit());
    String sql = EVENT_COLUMNS + EVENT_PAGE.formatted(where(conditions, "\n               and "));
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::eventSummary, parameters.toArray()),
        e -> new BikeschedException("Finding bikesched events failed", e));
  }

  public List<EventSummary> findEventsByIdRange(UUID from, UUID to, int limit) {
    String sql = EVENT_COLUMNS + EVENT_PAGE.formatted("\n               and e.id between ? and ?");
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::eventSummary, from, to, limit),
        e -> new BikeschedException("Finding bikesched events by id failed", e));
  }

  public Optional<EventSummary> findEvent(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_EVENT_SQL, AdminStore::eventSummary, eventId),
        e -> new BikeschedException("Reading bikesched event " + eventId + " failed", e));
  }

  public Optional<String> findEventPayload(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_EVENT_PAYLOAD_SQL, row -> row.string(1), eventId),
        e ->
            new BikeschedException(
                "Reading the payload of bikesched event " + eventId + " failed", e));
  }

  public List<EventNameSummary> findEventNames() {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_EVENT_NAMES_SQL,
                row -> new EventNameSummary(row.string(1), row.integer(2), row.integer(3))),
        e -> new BikeschedException("Counting bikesched events failed", e));
  }

  public List<EventStep> findEventSteps(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId");
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_EVENT_STEPS_SQL,
                row ->
                    new EventStep(
                        row.uuid(1),
                        row.string(2),
                        JobState.valueOf(row.string(3)),
                        row.string(4),
                        row.string(5)),
                eventId),
        e ->
            new BikeschedException(
                "Reading the steps of bikesched event " + eventId + " failed", e));
  }

  public List<WaitingStep> findWaitingSteps(String eventName, String keyPrefix, int limit) {
    Objects.requireNonNull(eventName, "eventName");
    var prefix =
        Sql.typed(keyPrefix == null || keyPrefix.isEmpty() ? null : keyPrefix, Types.VARCHAR);
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_WAITING_STEPS_SQL,
                row ->
                    new WaitingStep(
                        row.uuid(1),
                        row.string(2),
                        row.string(3),
                        row.string(4),
                        row.string(5),
                        row.instant(6),
                        row.instant(7)),
                eventName,
                prefix,
                prefix,
                limit),
        e -> new BikeschedException("Finding steps waiting for " + eventName + " failed", e));
  }

  public List<NodeInfo> findNodes(Duration nodeTimeout) {
    Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_NODES_SQL.formatted("order by n.name"),
                AdminStore::nodeInfo,
                Sql.seconds(nodeTimeout)),
        e -> new BikeschedException("Reading the bikesched nodes failed", e));
  }

  public Optional<NodeInfo> findNode(UUID nodeId, Duration nodeTimeout) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    return access.inReadOnlyTransaction(
        db ->
            db
                .list(
                    FIND_NODES_SQL.formatted("where n.id = ?"),
                    AdminStore::nodeInfo,
                    Sql.seconds(nodeTimeout),
                    nodeId)
                .stream()
                .findFirst(),
        e -> new BikeschedException("Reading bikesched node " + nodeId + " failed", e));
  }

  private static NodeInfo nodeInfo(Sql.Row row) throws SQLException {
    return new NodeInfo(
        row.uuid(1), row.string(2), row.instant(3), row.instant(4), row.bool(5), row.integer(6));
  }

  /** The conditions joined with {@code and}, after {@code prefix}; empty without conditions. */
  private static String where(List<String> conditions, String prefix) {
    return conditions.isEmpty() ? "" : prefix + String.join(" and ", conditions);
  }

  private static JobSummary jobSummary(Sql.Row row) throws SQLException {
    return new JobSummary(
        row.uuid(1),
        row.string(2),
        JobState.valueOf(row.string(3)),
        row.integer(4),
        row.integer(5),
        row.integer(6),
        row.instant(7),
        row.instant(8),
        row.instant(9),
        row.instant(10),
        row.string(11),
        row.uuid(12),
        row.string(13),
        row.string(14),
        row.string(15),
        row.string(16));
  }

  private static EventSummary eventSummary(Sql.Row row) throws SQLException {
    return new EventSummary(
        row.uuid(1),
        row.string(2),
        row.string(3),
        row.string(4),
        row.instant(5),
        row.instant(6),
        row.integer(7),
        row.bool(8));
  }
}
