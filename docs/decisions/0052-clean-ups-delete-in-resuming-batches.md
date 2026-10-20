---
title: "52. Clean-ups delete in resuming batches"
weight: 52
---

# 52. Clean-ups delete in resuming batches

Date: 2026-10-20

## Status

Accepted

## Context

Every polling node deletes ended jobs (with their steps, by `on delete cascade`), job history
entries and expired events after their retention. The job clean-up already deleted in batches of
1000, but the history and event clean-ups each deleted everything due in one statement. The job
history is the largest table (one row per change of a job or step), so a backlog (the history just
enabled, clean-ups failing for a while, a burst of jobs) became one long transaction: it holds back
the xmin horizon, so autovacuum cleans none of the tables meanwhile, writes a burst of WAL, and
with a `statementTimeout` it is cancelled and rolled back every time, so the backlog never shrinks.

Postgres keeps deleted rows as dead tuples until vacuum removes them, and their index entries
carry no visibility: an index scan visits each dead row's heap tuple until the entry is marked dead
or vacuumed. Each job batch looked for jobs past the retention from the front of the `finished_at`
index, so it read past every row the earlier batches had just deleted; DBOS describes such scans
taking minutes under load ([Scaling deletions in
Postgres](https://www.dbos.dev/blog/scaling-deletions-in-postgres)). `COMPENSATION_FAILED` jobs
also have `finished_at` but are never deleted, so they sat at the front of that index forever.

Alternatives considered:

- Drop the foreign key from steps to jobs and delete steps in a second phase, as DBOS does, for
  better cache locality. Not now: it gives up the guarantee that no step outlives its job, and no
  measurement shows the cascade to be the bottleneck at bikesched's volumes.
- Partition the history by time and drop old partitions. Free deletes, but partition maintenance
  in the migrator and the clean-up; reconsider if batched deletes are not enough.
- Run `VACUUM` from the library after a clean-up. It needs table ownership and cannot run in a
  transaction; tuning autovacuum is left to operators.

## Decision

All three clean-ups delete in batches of 1000 rows, each in its own transaction, oldest first by
their timestamp (`finished_at`, `created_at`, `expires_at`), skipping locked rows, until a batch
is not full. Each batch returns the timestamp of its newest row and the next batch starts there
(`>=`), so it never scans the entries earlier batches deleted. The `finished_at` index only covers
the states that are deleted (`SUCCEEDED`, `FAILED`, `CANCELLED`).

## Consequences

- A backlog is worked off in short transactions that stay below a `statementTimeout` and let
  autovacuum run in between.
- A row committed during a run with a timestamp before the batch's start (a long transaction
  finishing a job) is deleted by the next run instead.
- Steps are still deleted by the cascade; a load test of deletions would show whether a phased
  delete is worth it.
