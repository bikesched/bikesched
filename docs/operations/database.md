---
title: "Database"
weight: 3
---

# Database

All state lives in plain PostgreSQL tables prefixed `bikesched_`, in the data source's default
schema.

## Migrations

The schema is created by bikesched's own minimal migrator on first use (no Flyway or Liquibase).
Concurrent nodes are serialized with a PostgreSQL advisory lock; applied migrations are tracked with
checksums in `bikesched_schema_migrations`. The migration scripts ship in the jar under
`org/bikesched/migrations/`. `Bikesched.migrate()` runs the migration without registering the node
or polling, so an application can migrate at startup instead of on first use. The Spring starter
uses the same first-use initialization boundary ([Spring
Boot](../integrations/spring.md#schema-migration)).

### Disable automatic migration

Use the core builder flag when migrations belong in a separate deploy step:

```java
// Deployment: a data source with DDL rights, using the same bikesched library version.
try (var deploy = Bikesched.builder()
    .dataSource(migrationDataSource)
    .autoStartPolling(false)
    .build()) {
    deploy.migrate();
}

// Application: no DDL rights required.
var scheduler = Bikesched.builder()
    .dataSource(applicationDataSource)
    .migrateSchema(false)
    .build();
```

`migrateSchema` defaults to `true`. With `false`, first database use reads
`bikesched_schema_migrations` and requires every bundled version with its matching checksum.
It performs no DDL or history writes and does not acquire the migration advisory lock. Unknown
versions warn and are ignored, just as during migration, to allow rolling deployments. Validation
checks migration history, not the physical tables, columns or indexes: manually changed schema
objects are not detected.

With default polling, validation happens during `build()`, before node registration. With
`autoStartPolling(false)`, building stays database-free; validation waits for the first operation
that needs the database, including reads. A failure is retried on the next database operation.
Finish deployment before starting application access; validation does not wait for a concurrent
migration. Success is cached per instance, not checked before every query.

The application needs schema `USAGE`, `SELECT` on the migration history, and normal table/sequence
permissions for scheduler operations. A missing history table or bundled version fails with
instructions to run `Bikesched.migrate()` using a migration-capable data source. A checksum mismatch
names the version and script: restore matching scripts/library and schema, rather than changing
recorded checksums to bypass validation. SQL failures preserve their cause; check connectivity,
search path and permissions too. Merely executing the bundled SQL without recording bikesched's
migration history is not sufficient; the supported deploy path above maintains that history.

Explicit `Bikesched.migrate()` always runs the migrator, including when `migrateSchema(false)` is
set or validation already succeeded. It still needs DDL rights and never starts polling. In Spring,
set `bikesched.migrate-schema=false` for validation-only behavior. Flyway/Liquibase adapters and
configurable schemas are outside this feature.

> [!NOTE]
> While bikesched is unreleased, schema changes are made to the first migration in place. A local
> database created by an older build then fails the checksum check; recreate it, e.g. with
> `docker compose down -v`.

## Tables

| Table | Content |
|---|---|
| `bikesched_schema_migrations` | applied schema migrations with version, description, checksum, install time and duration; created by the migrator itself |
| `bikesched_nodes` | registered nodes with their id, name (not unique) and last heartbeat; dead nodes are removed by the others |
| `bikesched_jobs` | jobs with state `SCHEDULED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `COMPENSATING`, `COMPENSATION_FAILED` or `FAILING`, their payload, priority, attempts, timeout, lock (`locked_by_node_id`, the claiming node, and `lock_token`, new for every claim) and `last_error`; compensating jobs also carry the state they end in (`compensation_end_state`) and their `compensation_attempts`, failing jobs (whose [failure handler](../jobs/README.md#failure-handlers) runs) the state they end in (`failure_end_state`) and the runs of the handler (`failure_attempts`), jobs cancelled via `Bikesched.cancel` their `cancel_requested_at` and `cancel_message`; occurrences of recurring jobs also carry their `recurrence` description and, for fixed-rate recurrences, their `planned_run_at`; succeeded jobs their `result` ([Job results](../multi-step-jobs/child-jobs.md#job-results)), child jobs their `parent_job_id` (a plain reference, no foreign key: the parent may be deleted after its retention while the child runs), jobs scheduled with an [idempotency key](../jobs/README.md#idempotency-keys) their `idempotency_key` |
| `bikesched_steps` | durable steps of each job with their `kind` (`STEP`, `SLEEP`, `EVENT_WAIT`, `EVENT_PUBLISH`, `CHILD_JOB` or `POLL`), state `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILING` (attempts used up, or an event wait or poll timed out, failure handler yet to finish; its runs in `failure_attempts`), `FAILED`, `TIMED_OUT`, `COMPENSATED`, `CANCELLED` or `POSTPONED` (a `STEP` whose handler postponed the job, run again by the next attempt), their result, attempts, the node running them (`locked_by_node_id`, only while `RUNNING`) and whether they have a compensation (`compensable`; not for a step its failure handler resolved); event waits also carry `event_name`, `event_key` and their deadline (`wake_at`); once the event is received, its payload is copied into `result` and its id into `event_id` (a wait its failure handler resolved after a timeout has the fallback in `result`, no `event_id`, and keeps `last_error`); event publishes carry the published event's `event_name`, `event_key` and `event_id`; `event_id` is a plain reference (no foreign key), so it outlives the event's retention; sleeps carry their wake-up time (`wake_at`); polls carry the time of their next check (`wake_at`, while `WAITING`) and their deadline (`deadline_at`), and count their checks in `attempts`; child job steps carry the started child (`child_job_id`, a plain reference) and its type (`child_job_type`, so a replay starting another type under the same step name is rejected even after the child was deleted), the deadline of the parent's wait (`wake_at`) and, once the child ended, its terminal state (`child_end_state`), its result (`result`) and its error or cancel message (`last_error`) |
| `bikesched_events` | published events (name, key, payload, `published_at`, the optional `idempotency_key`), kept until `expires_at` (published + the event's retention), see [Retention](../multi-step-jobs/events.md#retention) and [Idempotency keys](../multi-step-jobs/events.md#idempotency-keys) |
| `bikesched_job_history` | only with the [job history](../jobs/README.md#job-history) enabled: one row per change of a job (`step_name` empty) or of one of its steps, with its time (`created_at`), the new `state`, the job's `attempt` count, the `node_id` that made the change and a `detail`, numbered by `seq`; kept for the job history retention, independently of the job (no foreign keys) |

## Text lengths

Names and keys are `text` columns, most of them indexed. To keep index entries well below
PostgreSQL's B-tree limit (about 2.7 kB), their length is limited, in characters:

| Value | Limit |
|---|---|
| node name, job type, step name, event name, recurrence description | 1 to 200 |
| event key, idempotency key | 1 to 500 |

bikesched checks the limits where a value is passed in (`IllegalArgumentException` naming the
parameter), and CHECK constraints enforce them in the schema.

Messages are diagnostic text and are never rejected, so recording a failure cannot fail on its
length: errors (`last_error` of jobs and steps) and cancel messages (`cancel_message`) longer than
4000 characters are stored truncated, ending in `… (truncated)`.

## Connection pool

Every store call borrows its own connection; nothing beyond the listening connection (see [Due jobs
start right away](../getting-started/configuration.md#due-jobs-start-right-away)) is held for
longer. Size the pool at least `maxConcurrentJobs` (jobs) plus a few (poller, heartbeat, clean-ups)
plus one more if `listenForDueJobs` is on. With `PGSimpleDataSource`, the data source used by the
examples and these docs, every statement opens a new TCP connection and authenticates; use a pool
(e.g. [HikariCP](https://github.com/brettwooldridge/HikariCP)) in production.

## Clean-ups

Every polling node deletes ended jobs with their steps (`jobRetention`), job history entries
(`jobHistory`) and expired events (`eventRetention`) on its own interval. Each clean-up deletes
in batches of 1000 rows, oldest first, each batch in its own short transaction, and skips rows
another node is deleting, so a backlog neither holds locks nor holds back vacuum for long, and
stays below a `statementTimeout`. A batch starts where the previous one ended instead of at the
oldest index entry, so it does not read past the rows deleted before vacuum removed them (see
[ADR 0052](../decisions/0052-clean-ups-delete-in-resuming-batches.md)).

Deleted rows take space until autovacuum reclaims them. With high job volumes, let autovacuum run
more often on the busy tables, for example:

```sql
alter table bikesched_jobs set (autovacuum_vacuum_scale_factor = 0.01);
alter table bikesched_steps set (autovacuum_vacuum_scale_factor = 0.01);
alter table bikesched_job_history set (autovacuum_vacuum_scale_factor = 0.01);
```

## Indexes and locks

Besides primary keys, the indexes cover job history cleanup, the cleanup of ended jobs (`finished_at`), polling (due `SCHEDULED`/`WAITING`/`COMPENSATING`/`FAILING` jobs in
claim order, as a partial index that excludes finished jobs; it includes `type`, so jobs of types
the node has no handlers for are skipped on index entries), jobs per node, at most one pending
occurrence per recurring job (partial unique index), idempotency keys of jobs (unique per type,
partial: only jobs with a key), steps waiting for an event, steps waiting for
a child job (by `child_job_id`), the steps that published or received an event (by `event_id`,
for the admin lookups), the pages of the admin's job list per type (`type, id`; ids are
time-ordered UUIDv7, so the primary keys serve the lists of all jobs and of all events), the admin's search by short id (the last 8 hex digits of a job or event id), and
event lookup and cleanup. The index of the jobs per node holds only running jobs, and
`bikesched_nodes` has no index besides its primary key, so heartbeats are HOT updates. Waiting for, suspending at and publishing events also take transaction-scoped advisory
locks per event name and key; no table is involved. Step writes share-lock the job's row while
checking that the job is still running with the writing attempt's `lock_token`, so a concurrent
release waits for them. A job suspending at a wait for a child job locks the child's step row
first; the child's end updates the same row, so the two serialize and the parent is never left
waiting for a child that already ended.
