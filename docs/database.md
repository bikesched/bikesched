# Database

All state lives in plain PostgreSQL tables prefixed `bikesched_`, in the data source's default
schema.

## Migrations

The schema is created by bikesched's own minimal migrator on first use (no Flyway or Liquibase).
Concurrent nodes are serialized with a PostgreSQL advisory lock; applied migrations are tracked with
checksums in `bikesched_schema_migrations`. The migration scripts ship in the jar under
`org/bikesched/migrations/`. `Bikesched.migrate()` runs the migration without registering the node
or polling, so an application can validate the schema at startup instead of on first use — the
Spring starter does this by default ([Spring Boot](spring.md)).

> [!NOTE]
> While bikesched is unreleased, schema changes are made to the first migration in place. A local
> database created by an older build then fails the checksum check; recreate it, e.g. with
> `docker compose down -v`.

## Tables

| Table | Content |
|---|---|
| `bikesched_schema_migrations` | applied schema migrations with version, description, checksum, install time and duration; created by the migrator itself |
| `bikesched_nodes` | registered nodes with their unique name and last heartbeat; dead nodes are removed by the others |
| `bikesched_jobs` | jobs with state `SCHEDULED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `COMPENSATING` or `COMPENSATION_FAILED`, their payload, priority, attempts, timeout, lock (`locked_by`, the claiming node, and `lock_token`, new for every claim) and `last_error`; compensating jobs also carry the state they end in (`compensated_state`) and their `compensation_attempts`, jobs cancelled via `Bikesched.cancel` their `cancel_requested_at` and `cancel_message`; occurrences of recurring jobs also carry their `recurrence` description and, for fixed-rate recurrences, their `fixed_rate_at`; succeeded jobs their `result` ([Job results](child-jobs.md#job-results)), child jobs their `parent_job_id` (a plain reference, no foreign key: the parent may be deleted after its retention while the child runs) |
| `bikesched_steps` | durable steps of each job with their `kind` (`STEP`, `SLEEP`, `EVENT_WAIT`, `EVENT_PUBLISH` or `CHILD_JOB`), state `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `COMPENSATED` or `CANCELLED`, their result, attempts, the node running them (`locked_by`, only while `RUNNING`) and whether they have a compensation (`compensable`); event waits also carry `event_name`, `event_key` and their deadline (`wait_until`); once the event is received, its payload is copied into `result` and its id into `event_id`; event publishes carry the published event's `event_name`, `event_key` and `event_id`; `event_id` is a plain reference (no foreign key), so it outlives the event's retention; sleeps carry their wake-up time (`wait_until`); child job steps carry the started child (`child_job_id`, a plain reference), the deadline of the parent's wait (`wait_until`) and, once the child ended, its terminal state (`child_state`), its result (`result`) and its error or cancel message (`last_error`) |
| `bikesched_events` | published events (name, key, payload, `published_at`), kept until `expires_at` (published + the event's retention), see [Retention](events.md#retention) |
| `bikesched_job_history` | only with the [job history](jobs.md#job-history) enabled: one row per change of a job (`step_name` empty) or of one of its steps, with its time (`created_at`), the new `state`, the job's `attempt` count, the `node_id` that made the change and a `detail`, numbered by `seq`; kept for the job history retention, independently of the job (no foreign keys) |

## Text lengths

Names and keys are `text` columns, most of them indexed. To keep index entries well below
PostgreSQL's B-tree limit (about 2.7 kB), their length is limited, in characters:

| Value | Limit |
|---|---|
| node name, job type, step name, event name, recurrence description | 1 to 200 |
| event key | 1 to 500 |

bikesched checks the limits where a value is passed in (`IllegalArgumentException` naming the
parameter), and CHECK constraints enforce them in the schema.

Messages are diagnostic text and are never rejected, so recording a failure cannot fail on its
length: errors (`last_error` of jobs and steps) and cancel messages (`cancel_message`) longer than
4000 characters are stored truncated, ending in `… (truncated)`.

## Indexes and locks

Besides primary keys, the indexes cover job history cleanup, the cleanup of ended jobs (`finished_at`), polling (due `SCHEDULED`/`WAITING`/`COMPENSATING` jobs in
claim order, as a partial index that excludes finished jobs; it includes `type`, so jobs of types
the node has no handlers for are skipped on index entries), jobs per node, at most one pending
occurrence per recurring job (partial unique index), steps waiting for an event, steps waiting for
a child job (by `child_job_id`), and event lookup and cleanup. Waiting for, suspending at and publishing events also take transaction-scoped advisory
locks per event name and key; no table is involved. Step writes share-lock the job's row while
checking that the job is still running with the writing attempt's `lock_token`, so a concurrent
release waits for them. A job suspending at a wait for a child job locks the child's step row
first; the child's end updates the same row, so the two serialize and the parent is never left
waiting for a child that already ended.

---

Previous: [Metrics](metrics.md) · [Documentation index](index.md)
