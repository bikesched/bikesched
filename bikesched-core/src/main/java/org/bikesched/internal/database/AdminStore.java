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
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.admin.EventNameSummary;
import org.bikesched.admin.EventQuery;
import org.bikesched.admin.EventStep;
import org.bikesched.admin.EventSummary;
import org.bikesched.admin.JobData;
import org.bikesched.admin.JobNameSummary;
import org.bikesched.admin.JobQuery;
import org.bikesched.admin.JobSummary;
import org.bikesched.admin.NodeInfo;
import org.bikesched.admin.RecurringJobRuns;
import org.bikesched.admin.StepInfo;
import org.bikesched.admin.WaitingStep;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.jspecify.annotations.Nullable;

/**
 * Read-only lookups across the tables for {@link org.bikesched.admin.Admin}; every method runs in
 * its own read-only transaction. Payloads and results are only read by {@link #findJobData}, {@link
 * #findStepResult} and {@link #findEventPayload}.
 */
public final class AdminStore {

  private final DatabaseAccess access;

  AdminStore(DatabaseAccess access) {
    this.access = access;
  }

  // The columns of a JobSummary from the jobs in %s; 'infinity' (a wait without a deadline)
  // reads as null.
  private static final String JOB_COLUMNS =
      """
            select j.id, j.name, j.type, j.state, j.attempts, j.max_attempts, j.priority,
                   nullif(j.scheduled_at, 'infinity') as scheduled_at, j.created_at, j.updated_at,
                   j.finished_at, j.last_error, j.parent_job_id, p.name as parent_job_name,
                   j.recurrence, j.idempotency_key, j.reference, n.id as node_id,
                   n.name as node_name
              from %s j
              left join bikesched_jobs p on p.id = j.parent_job_id
              left join bikesched_nodes n on n.id = j.locked_by_node_id""";

  // One page of jobs, newest first by their time-ordered ids, read along the primary key (or
  // bikesched_jobs_name_id_idx with a name, bikesched_jobs_reference_idx with a reference, or
  // bikesched_jobs_short_id_idx for an id search);
  // parents and nodes are joined to the page only.
  private static final String JOB_PAGE =
      """
            (select id, name, type, state, attempts, max_attempts, priority, scheduled_at, created_at,
                    updated_at, finished_at, last_error, parent_job_id, recurrence,
                    idempotency_key, reference, locked_by_node_id
               from bikesched_jobs%s
              order by id desc
              limit ?)""";

  private static final String FIND_JOB_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs") + "\n where j.id = ?";

  // Along the partial unique index on (name, idempotency_key).
  private static final String FIND_KEYED_JOB_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs") + "\n where j.name = ? and j.idempotency_key = ?";

  // The occurrences of a recurring job: the pending one along bikesched_jobs_recurring_idx, the
  // ended ones as a page along bikesched_jobs_name_id_idx.
  private static final String RECURRING_STATES =
      "('SCHEDULED', 'RUNNING', 'WAITING', 'COMPENSATING', 'FAILING')";

  private static final String FIND_PENDING_RUN_SQL =
      JOB_COLUMNS.formatted("bikesched_jobs")
          + "\n where j.name = ? and j.type = 'RECURRING' and j.state in "
          + RECURRING_STATES;

  private static final String FIND_ENDED_RUNS_SQL =
      JOB_COLUMNS.formatted(
              JOB_PAGE.formatted(
                  "\n              where name = ? and type = 'RECURRING' and state not in "
                      + RECURRING_STATES))
          + "\n order by j.id desc";

  private static final String FIND_JOB_DATA_SQL =
      "select payload::text as payload, result::text as result from bikesched_jobs where id = ?";

  // The columns of a StepInfo. A child job's state: its current state while it exists, the
  // recorded one after it was deleted.
  private static final String STEP_COLUMNS =
      """
            select s.id, s.job_id, s.name, s.type, s.state, s.attempts, s.failure_attempts,
                   s.compensable, s.last_error, s.started_at, s.finished_at,
                   nullif(s.wake_at, 'infinity') as wake_at, s.deadline_at, s.locked_by_node_id,
                   s.event_name, s.event_key, s.event_id, s.child_job_id, s.child_job_name,
                   coalesce(c.state, s.child_end_state) as child_job_state,
                   c.id is not null as child_job_exists
              from bikesched_steps s
              left join bikesched_jobs c on c.id = s.child_job_id""";

  // Along bikesched_steps_job_id_name_key.
  private static final String FIND_STEPS_SQL =
      STEP_COLUMNS + "\n where s.job_id = ?\n order by s.started_at, s.name";

  private static final String FIND_STEP_SQL = STEP_COLUMNS + "\n where s.id = ?";

  private static final String FIND_STEP_RESULT_SQL =
      "select coalesce(result::text, 'null') as result from bikesched_steps where id = ?";

  private static final String FIND_JOB_NAMES_SQL =
      """
            select name, state, count(*) as count, max(recurrence) as recurrence
              from bikesched_jobs group by name, state order by name, state""";

  // The columns of an EventSummary: receivers counted via bikesched_steps_event_id_idx; the
  // newest unexpired event of a name and key is the one a new wait receives.
  private static final String EVENT_COLUMNS =
      """
            select e.id, e.name, e.key, e.idempotency_key, e.reference, e.published_at, e.expires_at,
                   (select count(*) from bikesched_steps s
                     where s.event_id = e.id and s.type = 'EVENT_WAIT') as received_by,
                   e.expires_at > now() and not exists (
                       select 1 from bikesched_events l
                        where l.name = e.name and l.key = e.key and l.expires_at > now()
                          and (l.published_at, l.id) > (e.published_at, e.id)) as latest
              from bikesched_events e""";

  // One page of unexpired events, newest first by their time-ordered ids, read along the primary
  // key;
  // the select list is only computed for the rows of the page.
  private static final String EVENT_PAGE =
      """

             where e.expires_at > now()%s
             order by e.id desc
             limit ?""";

  private static final String FIND_EVENT_SQL = EVENT_COLUMNS + "\n where e.id = ?";

  private static final String FIND_EVENT_PAYLOAD_SQL =
      "select coalesce(payload::text, 'null') as payload from bikesched_events where id = ?";

  // The waiting steps use bikesched_steps_waiting_idx.
  private static final String FIND_EVENT_NAMES_SQL =
      """
            select name, sum(events)::int as events, sum(waiting)::int as waiting
              from (select name, count(*) as events, 0 as waiting from bikesched_events
                     where expires_at > now() group by name
                    union all
                    select event_name, 0, count(*) from bikesched_steps
                     where type = 'EVENT_WAIT' and state = 'WAITING' group by event_name) names
             group by name order by name""";

  private static final String FIND_EVENT_STEPS_SQL =
      """
            select s.id, s.job_id, j.name as job_name, j.state as job_state, s.name, s.type
              from bikesched_steps s
              join bikesched_jobs j on j.id = s.job_id
             where s.event_id = ? and s.type in ('EVENT_PUBLISH', 'EVENT_WAIT')
             order by s.started_at, s.job_id, s.name""";

  // Uses bikesched_steps_waiting_idx (event_name, event_key).
  private static final String FIND_WAITING_STEPS_SQL =
      """
            select s.id, s.job_id, j.name as job_name, s.name, s.event_name, s.event_key,
                   s.started_at, nullif(s.wake_at, 'infinity') as wake_at
              from bikesched_steps s
              join bikesched_jobs j on j.id = s.job_id
             where s.type = 'EVENT_WAIT' and s.state = 'WAITING' and s.event_name = ?
               and (?::text is null or starts_with(s.event_key, ?::text))
             order by s.started_at, s.job_id
             limit ?""";

  private static final String FIND_NODES_SQL =
      """
            select n.id, n.name, n.started_at, n.last_heartbeat_at,
                   n.last_heartbeat_at >= now() - make_interval(secs => ?) as alive,
                   (select count(*) from bikesched_jobs j where j.locked_by_node_id = n.id)::int
                     as running_jobs
              from bikesched_nodes n
             %s""";

  public List<JobSummary> findJobs(JobQuery query) {
    // Only the filters in use go into the statement: "? is null or ..." would keep the planner
    // from reading the page along an index.
    List<String> conditions = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    if (query.name() != null) {
      conditions.add("name = ?");
      parameters.add(query.name());
    }
    if (query.reference() != null) {
      conditions.add("reference = ?");
      parameters.add(query.reference());
    }
    if (!query.states().isEmpty()) {
      conditions.add("state = any(?)");
      parameters.add(Sql.array("text", query.states().stream().map(Enum::name).toArray()));
    }
    if (query.nodeId() != null) {
      conditions.add("locked_by_node_id = ?");
      parameters.add(query.nodeId());
    }
    if (query.beforeId() != null) {
      conditions.add("id < ?");
      parameters.add(query.beforeId());
    }
    parameters.add(query.limit());
    String sql =
        JOB_COLUMNS.formatted(JOB_PAGE.formatted(where(conditions, "\n              where ")))
            + "\n order by j.id desc";
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::jobSummary, parameters.toArray()),
        e -> new BikeschedException("Finding bikesched jobs failed", e));
  }

  // The short id narrows the search along bikesched_jobs_short_id_idx, the whole suffix decides.
  private static final String ID_SUFFIX =
      "right(id::text, 8) = ? and right(replace(id::text, '-', ''), ?) = ?";

  public List<JobSummary> findJobsByIdSuffix(String suffix, int limit) {
    String sql =
        JOB_COLUMNS.formatted(JOB_PAGE.formatted("\n              where " + ID_SUFFIX))
            + "\n order by j.id desc";
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::jobSummary, idSuffixParameters(suffix, limit)),
        e -> new BikeschedException("Finding bikesched jobs by id failed", e));
  }

  public RecurringJobRuns findRecurringJobRuns(String name, int limit) {
    return access.inReadOnlyTransaction(
        db ->
            new RecurringJobRuns(
                name,
                db.list(FIND_PENDING_RUN_SQL, AdminStore::jobSummary, name).stream()
                    .findFirst()
                    .orElse(null),
                db.list(FIND_ENDED_RUNS_SQL, AdminStore::jobSummary, name, limit)),
        e -> new BikeschedException("Finding the runs of recurring job " + name + " failed", e));
  }

  public Optional<JobSummary> findJob(UUID jobId) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_JOB_SQL, AdminStore::jobSummary, jobId),
        e -> new BikeschedException("Reading bikesched job " + jobId + " failed", e));
  }

  public Optional<JobSummary> findJob(String name, String idempotencyKey) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_KEYED_JOB_SQL, AdminStore::jobSummary, name, idempotencyKey),
        e -> new BikeschedException("Reading bikesched job by idempotency key failed", e));
  }

  public Optional<JobData> findJobData(UUID jobId) {
    return access.inReadOnlyTransaction(
        db ->
            db.one(
                FIND_JOB_DATA_SQL,
                row -> new JobData(row.string("payload"), row.string("result")),
                jobId),
        e -> new BikeschedException("Reading the data of bikesched job " + jobId + " failed", e));
  }

  public List<StepInfo> findSteps(UUID jobId) {
    return access.inReadOnlyTransaction(
        db -> db.list(FIND_STEPS_SQL, AdminStore::stepInfo, jobId),
        e -> new BikeschedException("Reading the steps of bikesched job " + jobId + " failed", e));
  }

  public Optional<StepInfo> findStep(UUID stepId) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_STEP_SQL, AdminStore::stepInfo, stepId),
        e -> new BikeschedException("Reading bikesched step " + stepId + " failed", e));
  }

  public Optional<String> findStepResult(UUID stepId) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_STEP_RESULT_SQL, row -> row.string("result"), stepId),
        e ->
            new BikeschedException(
                "Reading the result of bikesched step " + stepId + " failed", e));
  }

  private static StepInfo stepInfo(Sql.Row row) throws SQLException {
    return new StepInfo(
        row.uuid("id"),
        row.uuid("job_id"),
        row.string("name"),
        row.string("type"),
        row.string("state"),
        row.integer("attempts"),
        row.integer("failure_attempts"),
        row.bool("compensable"),
        row.string("last_error"),
        row.instant("started_at"),
        row.instant("finished_at"),
        row.instant("wake_at"),
        row.instant("deadline_at"),
        row.uuid("locked_by_node_id"),
        row.string("event_name"),
        row.string("event_key"),
        row.uuid("event_id"),
        row.uuid("child_job_id"),
        row.string("child_job_name"),
        row.enumValue("child_job_state", JobState.class),
        row.bool("child_job_exists"));
  }

  public List<JobNameSummary> findJobNames() {
    record Count(String name, JobState state, int count, String recurrence) {}
    List<Count> counts =
        access.inReadOnlyTransaction(
            db ->
                db.list(
                    FIND_JOB_NAMES_SQL,
                    row ->
                        new Count(
                            row.string("name"),
                            row.enumValue("state", JobState.class),
                            row.integer("count"),
                            row.string("recurrence"))),
            e -> new BikeschedException("Counting bikesched jobs failed", e));
    Map<String, Map<JobState, Integer>> byName = new LinkedHashMap<>();
    Map<String, String> recurrences = new LinkedHashMap<>();
    for (Count count : counts) {
      byName
          .computeIfAbsent(count.name(), name -> new EnumMap<>(JobState.class))
          .put(count.state(), count.count());
      if (count.recurrence() != null) {
        recurrences.putIfAbsent(count.name(), count.recurrence());
      }
    }
    List<JobNameSummary> names = new ArrayList<>(byName.size());
    byName.forEach(
        (name, byState) ->
            names.add(new JobNameSummary(name, byState, recurrences.get(name), false)));
    return names;
  }

  public List<EventSummary> findEvents(EventQuery query) {
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
    if (query.reference() != null) {
      conditions.add("e.reference = ?");
      parameters.add(query.reference());
    }
    if (query.beforeId() != null) {
      conditions.add("e.id < ?");
      parameters.add(query.beforeId());
    }
    parameters.add(query.limit());
    String sql = EVENT_COLUMNS + EVENT_PAGE.formatted(where(conditions, "\n               and "));
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::eventSummary, parameters.toArray()),
        e -> new BikeschedException("Finding bikesched events failed", e));
  }

  public List<EventSummary> findEventsByIdSuffix(String suffix, int limit) {
    String sql =
        EVENT_COLUMNS
            + EVENT_PAGE.formatted(
                "\n               and " + ID_SUFFIX.replace("id::text", "e.id::text"));
    return access.inReadOnlyTransaction(
        db -> db.list(sql, AdminStore::eventSummary, idSuffixParameters(suffix, limit)),
        e -> new BikeschedException("Finding bikesched events by id failed", e));
  }

  // The parameters of ID_SUFFIX and the limit, for a suffix of 8 to 32 lower-case hex digits.
  private static Object[] idSuffixParameters(String suffix, int limit) {
    return new Object[] {suffix.substring(suffix.length() - 8), suffix.length(), suffix, limit};
  }

  public Optional<EventSummary> findEvent(UUID eventId) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_EVENT_SQL, AdminStore::eventSummary, eventId),
        e -> new BikeschedException("Reading bikesched event " + eventId + " failed", e));
  }

  public Optional<String> findEventPayload(UUID eventId) {
    return access.inReadOnlyTransaction(
        db -> db.one(FIND_EVENT_PAYLOAD_SQL, row -> row.string("payload"), eventId),
        e ->
            new BikeschedException(
                "Reading the payload of bikesched event " + eventId + " failed", e));
  }

  public List<EventNameSummary> findEventNames() {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_EVENT_NAMES_SQL,
                row ->
                    new EventNameSummary(
                        row.string("name"), row.integer("events"), row.integer("waiting"))),
        e -> new BikeschedException("Counting bikesched events failed", e));
  }

  public List<EventStep> findEventSteps(UUID eventId) {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_EVENT_STEPS_SQL,
                row ->
                    new EventStep(
                        row.uuid("id"),
                        row.uuid("job_id"),
                        row.string("job_name"),
                        row.enumValue("job_state", JobState.class),
                        row.string("name"),
                        row.string("type")),
                eventId),
        e ->
            new BikeschedException(
                "Reading the steps of bikesched event " + eventId + " failed", e));
  }

  public List<WaitingStep> findWaitingSteps(
      String eventName, @Nullable String keyPrefix, int limit) {
    var prefix =
        Sql.typed(keyPrefix == null || keyPrefix.isEmpty() ? null : keyPrefix, Types.VARCHAR);
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_WAITING_STEPS_SQL,
                row ->
                    new WaitingStep(
                        row.uuid("id"),
                        row.uuid("job_id"),
                        row.string("job_name"),
                        row.string("name"),
                        row.string("event_name"),
                        row.string("event_key"),
                        row.instant("started_at"),
                        row.instant("wake_at")),
                eventName,
                prefix,
                prefix,
                limit),
        e -> new BikeschedException("Finding steps waiting for " + eventName + " failed", e));
  }

  public List<NodeInfo> findNodes(Duration nodeTimeout) {
    return access.inReadOnlyTransaction(
        db ->
            db.list(
                FIND_NODES_SQL.formatted("order by n.name"),
                AdminStore::nodeInfo,
                Sql.seconds(nodeTimeout)),
        e -> new BikeschedException("Reading the bikesched nodes failed", e));
  }

  public Optional<NodeInfo> findNode(UUID nodeId, Duration nodeTimeout) {
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
        row.uuid("id"),
        row.string("name"),
        row.instant("started_at"),
        row.instant("last_heartbeat_at"),
        row.bool("alive"),
        row.integer("running_jobs"));
  }

  /** The conditions joined with {@code and}, after {@code prefix}; empty without conditions. */
  private static String where(List<String> conditions, String prefix) {
    return conditions.isEmpty() ? "" : prefix + String.join(" and ", conditions);
  }

  private static JobSummary jobSummary(Sql.Row row) throws SQLException {
    return new JobSummary(
        row.uuid("id"),
        row.string("name"),
        row.enumValue("type", JobType.class),
        row.enumValue("state", JobState.class),
        row.integer("attempts"),
        row.integer("max_attempts"),
        row.integer("priority"),
        row.instant("scheduled_at"),
        row.instant("created_at"),
        row.instant("updated_at"),
        row.instant("finished_at"),
        row.string("last_error"),
        row.uuid("parent_job_id"),
        row.string("parent_job_name"),
        row.string("recurrence"),
        row.string("idempotency_key"),
        row.string("reference"),
        row.uuid("node_id"),
        row.string("node_name"));
  }

  private static EventSummary eventSummary(Sql.Row row) throws SQLException {
    return new EventSummary(
        row.uuid("id"),
        row.string("name"),
        row.string("key"),
        row.string("idempotency_key"),
        row.string("reference"),
        row.instant("published_at"),
        row.instant("expires_at"),
        row.integer("received_by"),
        row.bool("latest"));
  }
}
