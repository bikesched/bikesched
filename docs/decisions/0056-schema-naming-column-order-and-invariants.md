---
title: "56. Schema naming, column order and invariants"
weight: 56
---

# 56. Schema naming, column order and invariants

Date: 2026-10-23

## Status

Accepted

## Context

A review of the schema from a database administrator's view found a few columns named against the
schema's own conventions (references end in `_id`, timestamps in `_at`, one concept one name),
columns in an order that left padding between fixed-width values, and invariants the stores keep
that the schema did not check.

## Decision

- Renamed: `bikesched_steps.wait_until` to `wake_at`, `bikesched_steps.timeout_at` to
  `deadline_at` (it is a deadline; "timeout" stays the duration in `timeout_millis`), `locked_by`
  to `locked_by_node_id` (jobs and steps), `compensated_state` and `failing_state` to
  `compensation_end_state` and `failure_end_state`, `child_state` to `child_end_state`,
  `fixed_rate_at` to `planned_run_at`, and `bikesched_schema_migrations.execution_time_ms` to
  `execution_time_millis`.
- Columns are ordered by alignment: uuids, then timestamps and bigints, then integers and
  booleans, then text and jsonb. Measured, this saves 0 to 8 bytes per row; it is done for
  readability and because it is free while V001 is edited in place.
- New CHECKs: a job's `locked_at` goes with its lock, exactly ended jobs have `finished_at`,
  `planned_run_at` only on recurring occurrences, a cancel message only with a cancel request, no
  job is its own parent; unfinished steps have no `finished_at` and ended ones (except
  `COMPENSATED`) have it, step counters are not negative; history attempts are not negative.
  (Rejected: a node's heartbeat not before its start; tests simulate dead nodes by moving the
  heartbeat back, and the database clock guarantees it anyway.) Primary and foreign keys are named explicitly, and the
  name checks of `bikesched_steps` are split per column, so a violation names the column.

## Consequences

- Local databases need `docker compose down -v` (V001 changed in place, as usual while
  unreleased).
- A store bug breaking one of the invariants now fails its statement instead of leaving an
  inconsistent row.
