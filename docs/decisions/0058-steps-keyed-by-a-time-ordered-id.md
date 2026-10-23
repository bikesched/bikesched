---
title: "58. Steps keyed by a time-ordered id"
weight: 58
---

# 58. Steps keyed by a time-ordered id

Date: 2026-10-23

## Status

Accepted

## Context

`bikesched_steps` was keyed by `(job_id, name)`. Every write to a step repeated the job id and
the step name, up to 600 bytes, and the admin had no single value to point at one step: the job
detail listed steps by name, and the event pages named the receiving step but could not link to
it. A step had no identity of its own to show, search for or hand to other tools.

Alternatives considered: keeping the composite primary key and adding a unique `id` column next
to it (the same two indexes, but the table's identity stays the name), and creating the id with
PostgreSQL 18's `uuidv7()` as a column default (the insert would then have to return it, and
bikesched creates every other id in Java, see [ADR 0054](0054-time-ordered-ids.md)).

## Decision

Every step gets an `id`, a UUIDv7 created by the library (`UuidUtil`) when the step is first
inserted. It is the primary key of `bikesched_steps`; the name stays unique within its job
(`bikesched_steps_job_id_name_key`), which the handler's replay, the upsert that starts a step
attempt (`on conflict (job_id, name)`) and the lookups of a job's steps use.

- The id is kept for the life of the step: a later attempt, a further check of a poll, a
  restarted child job step or a compensation updates the same row, so its id never changes.
- Every write to a single step addresses it by its id: the statement that first finds or creates
  the step returns the id, and the store methods that change the step take it (`StepRef`: id and
  name, the name for the job history and messages). Writes to several steps at once stay
  set-based: cancelling a job's steps (`job_id`), completing the steps waiting for an event
  (`event_name`, `event_key`) and the step of a child job that ended (`child_job_id`).
- The admin reads a step by its id (`Admin.findStep`, `Admin.findStepResult`) and shows it on its
  own page, `#/jobs/{jobId}/steps/{stepId}`, served by `GET /api/steps/{id}`. `StepInfo`,
  `EventStep` and `WaitingStep` carry the step id, so the job detail, its history and the event
  pages link to it.
- The job history keeps naming the step (`step_name`): a step id per history row would grow the
  largest table, and the job id and step name identify the step there.

## Consequences

- The primary key of `bikesched_steps` is a 16-byte UUID filled in insertion order instead of a
  key with the step name; the table keeps two unique indexes, one led by the job id.
- Updates of a step go through the narrower primary key; the number of statements per step is
  unchanged.
- The id is internal to the store and the admin for now: the `Step` handed to handlers does not
  expose it, see the idea "Stable step id for external callbacks" in `IDEAS.md`.
- An id reveals when its step first started.
