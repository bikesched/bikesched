# Database

All state lives in plain PostgreSQL tables prefixed `bikesched_`, in the data source's default
schema.

## Migrations

The schema is created by bikesched's own minimal migrator on first use (no Flyway or Liquibase).
Concurrent nodes are serialized with a PostgreSQL advisory lock; applied migrations are tracked with
checksums in `bikesched_schema_migrations`. The migration scripts ship in the jar under
`org/bikesched/migrations/`.

> [!NOTE]
> While bikesched is unreleased, schema changes are made to the first migration in place. A local
> database created by an older build then fails the checksum check; recreate it, e.g. with
> `docker compose down -v`.

## Tables

| Table | Content |
|---|---|
| `bikesched_schema_migrations` | applied schema migrations with version, description, checksum, install time and duration; created by the migrator itself |
| `bikesched_nodes` | registered nodes with their unique name and last heartbeat; dead nodes are removed by the others |
| `bikesched_jobs` | jobs with state `SCHEDULED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `COMPENSATING` or `COMPENSATION_FAILED`, their payload, priority, attempts, timeout, lock (`locked_by`) and `last_error`; compensating jobs also carry the state they end in (`compensated_state`) and their `compensation_attempts`, jobs cancelled via `Bikesched.cancel` their `cancel_requested_at` and `cancel_message`; occurrences of recurring jobs also carry their `recurrence` description and, for fixed-rate recurrences, their `fixed_rate_at` |
| `bikesched_steps` | durable steps of each job with state `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `COMPENSATED` or `CANCELLED`, their result, attempts and whether they have a compensation (`compensable`); steps waiting for an event also carry `event_name`, `event_key` and their deadline (`wait_until`) |
| `bikesched_events` | published events (name, key, payload), kept for the event retention period |

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

Besides primary keys, the indexes cover polling (due `SCHEDULED`/`WAITING`/`COMPENSATING` jobs in
claim order, as a partial index that excludes finished jobs), jobs per node, at most one pending
occurrence per recurring job (partial unique index), steps waiting for an event, and event lookup
and cleanup. Waiting for, suspending at and publishing events also take transaction-scoped advisory
locks per event name and key; no table is involved.

---

Previous: [Kotlin](kotlin.md) · [Documentation index](index.md)
