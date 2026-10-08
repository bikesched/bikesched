# Specification: incidents for permanently failed jobs

Status: **draft**, open questions in §9.

## 1. Goal

A job that fails permanently needs someone's attention: an operator looks at the error, fixes the
cause and runs the job again, or decides nothing needs to be done. bikesched records each such
failure as an **incident**, durably and in the same transaction that ends the job, so it is never
lost, and lets an operator resolve it, through the API today and a CLI or admin UI later:

```java
Bikesched bikesched =
    Bikesched.builder()
        .dataSource(dataSource)
        .incidents(
            IncidentSettings.builder()
                .jobType("charge", "alert-payments") // failed job type -> incident job type
                .defaultJobType("alert-oncall") // optional, for all other job types
                .build())
        .build();

for (Incident incident : bikesched.findIncidents(IncidentState.OPEN, 50)) {
  log.info("{} failed: {}", incident.jobType(), incident.error());
}
bikesched.retryIncident(incidentId, "fixed the payment provider config");
bikesched.resolveIncident(otherIncidentId, "refunded manually");
```

## 2. Today

- A job ends `FAILED` when its last attempt fails, or after its compensations ran
  (`compensated_state = 'FAILED'`); it ends `COMPENSATION_FAILED` when its compensations failed in
  all attempts. All three transitions are single statements in `JobStore` (`FAIL_JOB_SQL`,
  `COMPLETE_COMPENSATION_SQL`, `FAIL_COMPENSATION_SQL`). Timeouts and abandoned attempts end there
  too; jobs of dead nodes are released and fail on a later attempt.
- `JobListener.onAttemptEnded` reports the failure after the commit, by the node recording it; it is
  lost if that node dies in between (`IDEAS.md`, "Hook for permanently failed jobs").
- `Bikesched.retry(jobId)` (ADR 0017) runs a `FAILED` job again, except occurrences of recurring
  jobs and child jobs.
- The job retention (ADR 0012) deletes `FAILED` jobs after 7 days, so a failure nobody looked at
  disappears, and `retry` with it. `COMPENSATION_FAILED` jobs are kept forever.
- Nothing records that someone looked at a failed job.

## 3. Non-goals

- Incidents for anything but permanently failed jobs (expired event waits, dead nodes, …).
- Retrying compensations of a `COMPENSATION_FAILED` job, and retrying a failed occurrence of a
  recurring job (§4.4): both incidents can only be resolved.
- The CLI and the admin UI themselves: this spec provides the API and table they build on.
- Notification channels (mail, Slack, …): the incident job (§4.5) is user code.

## 4. Design

### 4.1 Opt-in

Incidents are off by default (`AGENTS.md`: new mechanisms are opt-in). They are enabled with
`BikeschedBuilder.incidents(IncidentSettings)`. Like the job history, the setting takes effect on
the node that fails a job, so it should be enabled on every node; a node without it opens no
incidents. The table exists either way (it is part of `V001`).

### 4.2 Table

```sql
create table bikesched_incidents (
    id          uuid        primary key,
    job_id      uuid        not null references bikesched_jobs (id) on delete cascade,
    job_type    text        not null,
    job_state   text        not null,   -- FAILED or COMPENSATION_FAILED
    error       text,                   -- the job's last_error when the incident opened
    state       text        not null default 'OPEN',
    resolution  text,                   -- RETRIED or RESOLVED, once resolved
    comment     text,                   -- the operator's comment, once resolved
    created_at  timestamptz not null default now(),
    resolved_at timestamptz,
    constraint bikesched_incidents_job_type_length check (char_length(job_type) <= 200),
    constraint bikesched_incidents_job_state_check check (job_state in ('FAILED', 'COMPENSATION_FAILED')),
    constraint bikesched_incidents_state_check
        check (state = 'OPEN' and resolution is null and resolved_at is null
               or state = 'RESOLVED' and resolution in ('RETRIED', 'RESOLVED') and resolved_at is not null)
);
-- at most one open incident per job
create unique index bikesched_incidents_open_job_idx on bikesched_incidents (job_id) where state = 'OPEN';
-- a job's incidents (findJobIncidents, JobInfo.openIncidentId, the cascade from bikesched_jobs)
create index bikesched_incidents_job_id_idx on bikesched_incidents (job_id);
create index bikesched_incidents_state_created_at_idx on bikesched_incidents (state, created_at);
```

- One row per permanent failure: a job that is retried and fails again gets a second incident, so
  the table is also the job's failure record.
- `error` is copied, because a retry overwrites the job's `last_error` on its next failure.
  `error` and `comment` have no CHECK; the store truncates them (`ValidationUtil.truncateMessage`).
- `job_type` is copied so listing incidents needs no join and the incident job (§4.5) gets it.

### 4.3 Opening an incident

The three failing statements become CTEs that also insert the incident (and the incident job,
§4.5) when the job ended `FAILED` or `COMPENSATION_FAILED`, the node has incidents enabled (a
parameter) and the job is not a child job (`parent_job_id is null`). One statement, so the
incident commits with the job's state or not at all, wherever the job fails.

Child jobs get no incident: their parent receives the outcome (`JobResult.Failed`,
`JobResult.CompensationFailed`) and decides. If the parent fails because of it, the parent gets the
incident.

The change is recorded in the job history (if enabled) as "incident opened". The node logs it
(`INFO`, with job id, type and incident id).

### 4.4 Resolving an incident

New methods on `Bikesched`:

| Method | Effect |
|---|---|
| `Optional<Incident> findIncident(UUID incidentId)` | the incident, if it exists |
| `List<Incident> findIncidents(IncidentState state, int limit)` | incidents in that state, oldest first |
| `List<Incident> findJobIncidents(UUID jobId)` | all incidents of a job, oldest first |
| `boolean retryIncident(UUID incidentId, String comment)` | retries the job like `retry(jobId)` and resolves the incident as `RETRIED`, in one transaction; `false` if the incident is not open |
| `boolean resolveIncident(UUID incidentId, String comment)` | resolves the incident as `RESOLVED` without running the job; `false` if it is not open |

- `retryIncident` throws `IllegalArgumentException` for incidents it cannot retry, like `retry`
  does: `COMPENSATION_FAILED` jobs and failed occurrences of recurring jobs. Those can only be
  resolved. A later rerun of a failed occurrence as a one-off job (recurrence cleared, so it does
  not collide with the next occurrence) goes into `IDEAS.md`.
- `Bikesched.retry(jobId)` on a job with an open incident resolves it as `RETRIED` as well (no
  comment), so both paths leave the same state.
- `comment` may be `null`. Who resolved an incident is not recorded by bikesched; a CLI or UI puts
  it into the comment (open question 3).
- Both are recorded in the job history ("incident retried", "incident resolved").

From a failed job to its incident: `JobInfo` gets `openIncidentId()` (`Optional<UUID>`), filled
by `findJob` with a lookup on `bikesched_incidents_job_id_idx`; `findJobIncidents(jobId)` returns
all of them. The job stores no reference itself: a job can have several incidents (retried, failed
again), and a column would have to be kept in sync on every resolution. The opposite direction,
from an incident job to the incident it handles, is a column (§4.5).

`Incident` is an interface in the new exported package `org.bikesched.incident`, with
`IncidentState` (`OPEN`, `RESOLVED`) and `IncidentResolution` (`RETRIED`, `RESOLVED`):
`id()`, `jobId()`, `jobType()`, `jobState()` (`JobState`), `error()`, `state()`,
`resolution()` (`Optional`), `comment()` (`Optional`), `createdAt()`, `resolvedAt()` (`Optional`).

### 4.5 Incident jobs

An incident can schedule a job, so code reacts to it with the job machinery's guarantees
(retries, timeouts) instead of an outbox: alerting, moving the payload to a dead-letter store,
opening a ticket.

`IncidentSettings` maps failed job types to incident job types:

```java
IncidentSettings.builder()
    .jobType("charge", "alert-payments")
    .jobType(ORDER, ALERT) // JobType overloads
    .defaultJobType("alert-oncall") // optional
    .build();
```

- The node that fails a job looks up its type before the statement: the mapped type, else the
  default, else none. This works because a node only claims job types it has handlers for (ADR
  0016), so the node failing a job of type X registered X, and its settings decide for X. Nodes
  should share the mapping, as they share handlers.
- The incident job is inserted by the same statement as the incident, due right away, with the
  default priority and attempts, and an `IncidentJobPayload` JSON payload, built in SQL:
  `{"incidentId": …, "jobId": …, "jobType": …, "jobState": …, "error": …}`. `IncidentJobPayload`
  is a record in `org.bikesched.incident`, so typed handlers use
  `JobType.of("alert-payments", IncidentJobPayload.class)`.
- The incident job references its incident in a new column
  `bikesched_jobs.created_by_incident_id uuid references bikesched_incidents (id) on delete set null`, set
  only on incident jobs. A CLI or UI can show per incident whether its job ran.
- No loops: a failing incident job opens an incident like any job (a broken alert is not silent),
  but the failing statement schedules no incident job for a job with `created_by_incident_id` set. The marker
  is stored with the job, so it does not depend on the nodes' mappings agreeing or staying the
  same between scheduling and failure.
- Without a handler for the incident job type anywhere, the job waits like any unhandled job.

Rejected alternatives:

- Recognizing incident jobs by their type being one of the mapped incident job types: depends on
  every node having the same mapping, and breaks when the mapping changes while incident jobs are
  pending.
- Incident job type per job (`newJob(type).incidentJobType(…)`, stored in a column): survives
  inconsistent node configs, but needs a column and a setting on every scheduling call; the
  mapping belongs with the handlers.
- A guaranteed `JobListener` callback via an outbox table: re-implements claiming and retries,
  which jobs already have.

### 4.6 Retention

- A `FAILED` or `COMPENSATION_FAILED` job with an open incident is never deleted by the job
  retention, so it can still be retried and its payload inspected.
- Once its incidents are resolved, a `FAILED` job is deleted after the retention as before,
  counted from the later of `finished_at` and the last `resolved_at`, so resolving an old incident
  does not delete the job right away. Its incidents go with it (`on delete cascade`).
- `COMPENSATION_FAILED` jobs stay kept forever as today (ADR 0012), with or without incidents
  (open question 2).
- Without incidents enabled nothing changes.

### 4.7 Metrics

`bikesched-micrometer` gets a gauge `bikesched.incidents.open` (tag `job.type`) from a periodic
count query, since an open incident is state in the database, not an event on one node. It is
registered separately from the listeners (`MicrometerIncidentMetrics.bindTo(registry, bikesched)`),
so nodes sharing a registry register it once. Alerting on it does not depend on any node
surviving a commit.

### 4.8 Spring and Kotlin

- Starter: `bikesched.incidents.enabled`, `bikesched.incidents.job-types.<failed-type>=<incident-type>`
  and `bikesched.incidents.default-job-type`; an `IncidentSettings` bean wins over the properties.
- Kotlin: `incidents { jobType("charge", "alert-payments") }` in the builder DSL, and
  `findIncidentOrNull`.

## 5. Rejected alternatives

- No new entity, the `FAILED` job is the incident (a `resolved_at` column on `bikesched_jobs`):
  smaller, but a retried job that fails again overwrites its first failure, and an operator inbox
  would filter jobs by state and flags instead of listing incidents.
- Only an incident job type (a dead-letter job), no table: guarantees the reaction but gives
  operators nothing to list and resolve.
- Incidents for child jobs: duplicate the parent's decision; the parent's own incident covers it.

## 6. Documentation

New `docs/incidents.md` (linked from `docs/index.md` and `jobs.md`), builder option in
`docs/configuration.md`, table in `docs/database.md`, gauge in `docs/metrics.md`, `docs/spring.md`,
`docs/kotlin.md`, ADR; replace the `IDEAS.md` entry with the recurring-occurrence rerun idea.

## 7. Testing

- `IncidentTest` (core): an incident opens when a job fails its last attempt, after compensation
  (`FAILED`) and on `COMPENSATION_FAILED`; none while attempts remain, none for child jobs, none
  without the setting; the incident job is scheduled with the payload for mapped and default types
  and not for incident jobs; `retryIncident` reruns the job and resolves, `resolveIncident` only
  resolves, both return `false` for resolved incidents; `retryIncident` throws for recurring
  occurrences and `COMPENSATION_FAILED`; `retry(jobId)` resolves the open incident; a second
  failure opens a second incident; job history entries; log lines.
- `JobCleanUpTest`: jobs with open incidents are kept, resolved ones deleted after the retention
  from `resolved_at`.
- `SchemaMigratorTest`: the new table; `bikesched_jobs.created_by_incident_id`.
- Micrometer gauge, starter properties, Kotlin DSL.

## 8. Implementation steps

1. Table, `org.bikesched.incident` API, settings, opening incidents in the three statements,
   find/retry/resolve, retention, tests.
2. Incident jobs with tests.
3. Micrometer gauge, starter and Kotlin wiring.
4. Docs, ADR, `IDEAS.md`.

## 9. Open questions

1. Should `findIncidents` take a filter object (job type, created before/after, cursor paging) for
   the CLI right away, or start with state and limit?
2. Should resolved `COMPENSATION_FAILED` jobs fall to the retention when incidents are enabled?
   Today they are kept forever because they need attention; a resolved incident says they no
   longer do.
3. Record who resolved an incident (`resolved_by text`), or leave it to the comment?

## 10. Decisions

- Opt-in via `BikeschedBuilder.incidents(IncidentSettings)` (§4.1).
- `COMPENSATION_FAILED` jobs and failed occurrences of recurring jobs can only be resolved (§4.4).
- Incident job types are mapped per failed job type in the incident settings (§4.5).
- Incident jobs reference their incident (`bikesched_jobs.created_by_incident_id`) and never schedule another incident job (§4.5).
