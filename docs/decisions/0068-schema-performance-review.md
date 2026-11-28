---
title: "68. Schema performance review: null deadlines, indexes and column order"
weight: 68
---

# 68. Schema performance review: null deadlines, indexes and column order

Date: 2026-11-28

## Status

Accepted; amends [ADR 0054](0054-time-ordered-ids.md) (drops the short id search) and
[ADR 0056](0056-schema-naming-column-order-and-invariants.md) (column order rule)

## Context

A database performance review of `V001` (not yet released, so changed in place) found several
issues on the job queue's hot paths and some drift between the schema and its own conventions:

- `scheduled_at` used the sentinel `'infinity'` for "waiting without a deadline" instead of
  `NULL`. Every indefinitely `WAITING` job stayed in `bikesched_jobs_poll_idx`, which polling can
  never claim, and the sentinel leaked into several read queries as `nullif(..., 'infinity')`.
- `bikesched_jobs_short_id_idx` and `bikesched_events_short_id_idx` indexed `right(id::text, 8)`,
  the random tail of a UUIDv7. Unlike every other index on these tables (append-only, following
  the ids' creation order), every insert and retention delete touched a random leaf page of these
  two indexes for an admin-only convenience search.
- `bikesched_history_created_at_idx` and `bikesched_jobs_recurring_idx`'s sibling indexes
  were not keyset-friendly for `(created_at, id)` paging, forcing an incremental sort on
  `HistoryStore.findHistory`.
- `bikesched_events_name_key_idx` had no `id` tiebreaker and no `include (expires_at)`, so
  `EventStore.LATEST_EVENT_SQL` and `AdminStore`'s event lookups needed a heap fetch per row.
- `bikesched_nodes`, `bikesched_jobs` and `bikesched_steps` left PostgreSQL's default
  `fillfactor` (100), even though `bikesched_nodes`' comment already assumed HOT heartbeat
  updates.
- Three `CHECK` constraints relied on SQL's three-valued logic passing a `NULL` operand through
  `AND`/`IN` silently (`parent_job_id <> id`, `child_end_state in (...)`, `jsonb_typeof(data) =
  'object'`), which breaks if one is later refactored into an `OR`.
- `bikesched_schema_migrations` (created by `SchemaMigrator`, not `V001`) used an inline `primary
  key` instead of a named constraint, had no `collate "C"` on `checksum`, and its columns were not
  ordered like the rest of the schema.
- `bikesched_steps.child_end_state` was the only `child_job_*` column not following that prefix.

## Decision

- `bikesched_jobs.scheduled_at` is nullable; `NULL` means "waiting without a deadline" (a
  `CHECK` only allows it while `state = 'WAITING'`). `bikesched_jobs_poll_idx` adds
  `and scheduled_at is not null`, so indefinitely waiting jobs are never polled for. Reads that
  used to read 'infinity' as `null` (`AdminStore`, `StepStore`) now read the column directly.
- Dropped `bikesched_jobs_short_id_idx`, `bikesched_events_short_id_idx`,
  `Admin.findJobsByIdSuffix`/`findEventsByIdSuffix` and the admin API's id-suffix search: the
  admin UI's search field now only looks up a full id (`Admin.findJob`/`findEvent`), alongside the
  unchanged reference search. The UI still shows a shortened id for readability (`shortId`,
  unchanged), just without a server-side suffix lookup.
  Kept `bikesched_jobs_poll_idx` as the only index for `JobStore.nextDueIn` (no added
  `(scheduled_at, name)` index): most jobs use a handful of priority levels, where PostgreSQL 18's
  B-tree skip scan serves it well, and bikesched targets PostgreSQL 18 and later.
- `bikesched_history_created_at_idx` and `bikesched_history_user_idx` are now `(created_at, id)`;
  `HistoryStore.findHistory`'s keyset predicate is a plain row comparison
  `(created_at, id) < (?, ?)`. `bikesched_events_name_key_idx` gains `id desc` and
  `include (expires_at)`; a new partial index `bikesched_events_idempotency_key_idx (name, key,
  idempotency_key) include (expires_at)` serves `EventStore.FIND_KEYED_EVENT_SQL` without
  widening the main index.
- `fillfactor = 70` on `bikesched_nodes`, `fillfactor = 85` on `bikesched_jobs` and
  `bikesched_steps`, leaving room for HOT updates on their frequently updated columns.
- The three `CHECK`s above are now explicit about `NULL`:
  `parent_job_id is distinct from id`, `child_job_end_state is null or child_job_end_state in
  (...)`, `data is null or jsonb_typeof(data) = 'object'`.
- `bikesched_schema_migrations` gets a named `primary key` constraint, `checksum collate "C"`,
  and the same column order as the rest of the schema.
- `bikesched_steps.child_end_state` renamed to `child_job_end_state`.

### Column order

Amends ADR 0056: columns are ordered for PostgreSQL's physical row layout instead of readability,
applied the same way to every table: `NOT NULL` fixed-width columns first (`uuid`, then
`timestamptz`/`bigint`, then `integer`, then `boolean`), then nullable fixed-width columns in the
same order, then variable-length columns (`NOT NULL` `text`, nullable `text`, `jsonb`). This
removes alignment padding and keeps PostgreSQL's cached attribute offsets (`attcacheoff`) covering
every fixed-width column, not just the ones before the first `text` column. `bikesched_nodes` and
`bikesched_events` already followed this layout; `bikesched_jobs`, `bikesched_steps` and
`bikesched_history` are reordered to match (their `id`, `name`, `type`, `state` no longer lead).

Rejected: keeping identifying columns (`id`, `name`, `type`, `state`) first for `SELECT *`
readability, as ADR 0056 chose: inconsistent with `bikesched_nodes`/`bikesched_events`, and
costs 0 to several bytes of padding per row plus a shorter `attcacheoff` fast path on every read
of `bikesched_jobs` and `bikesched_steps`, the two largest and most frequently read/written
tables.

## Consequences

- Local databases need `docker compose down -v` (`V001` changed in place, as usual while
  unreleased).
- `Admin.findJobsByIdSuffix`/`findEventsByIdSuffix` are removed; applications depending on them
  switch to `findJob(UUID)`/`findEvent(UUID)` or a reference search.
- Indefinitely waiting jobs (an event wait, a child job wait or a batch wait without a timeout)
  have `scheduled_at = null`; code reading that column directly (not through the stores) must
  handle `null` instead of `'infinity'`.
