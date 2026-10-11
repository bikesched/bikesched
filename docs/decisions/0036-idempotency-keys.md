---
title: "36. Idempotency keys as columns, taken while the job exists"
weight: 36
---

# 36. Idempotency keys as columns, taken while the job exists

Date: 2026-10-11

## Status

Accepted.

## Context

A caller that retries (a webhook delivered twice, a request retried after a timeout) scheduled the
same job or published the same event twice; callers had to deduplicate in a table of their own.
Request-level idempotency keys (a key record with a lock, a recovery point, the request parameters
and the stored response) make an API request resumable. A bikesched job already is: its steps are
the recovery points, its claim the lock, the poller the completer and the job row the outcome. A
key only has to deduplicate creating the job or event.

The alternatives were a dedicated key table referenced by the jobs and events, a key freed once the
job ended (a partial unique index over the unended states, like the recurring jobs'), globally
unique job keys, and a runtime check rejecting a key on a recurring job.

## Decision

`JobBuilder.idempotencyKey(String)` continues with an `IdempotentJobBuilder` (one-off `schedule`
methods and `connection(…)`, no `schedule(Recurrence)`); `TransactionalJobBuilder` and
`NodeEventBuilder` get `idempotencyKey(String)`. Keys have 1 to 500 characters.

- Jobs: column `bikesched_jobs.idempotency_key` with a partial unique index on `(type,
  idempotency_key) where idempotency_key is not null`. Scheduling inserts with `on conflict do
  nothing`; without a row, it selects and returns the existing job's id (and inserts again, once,
  if that job was deleted in between). The key is taken until the job retention deletes the job,
  whatever its end state.
- Events: column `bikesched_events.idempotency_key`, no index. Publishing checks for an unexpired
  event with the same name, key and idempotency key under the advisory lock it already takes on
  name and key, and returns that event's id. A partial index can't express "unexpired" (`now()`
  is not immutable).
- A duplicate is ignored, whatever its payload or settings: nothing is stored, nothing made due,
  no history entry, no `onEventPublished`. The new job's `SCHEDULED` history entry names the key.
- Child jobs, events published by a job and recurring jobs have no key: their step or the
  one-pending-occurrence rule already makes them unique.

## Consequences

- A webhook redelivered after its job succeeded, failed or was cancelled returns that job: the key
  only frees once the job is deleted. Another try after a definitive outcome needs a new key.
- Jobs without a key cost nothing: the partial index has no entry for them, and since neither
  `type` nor the key ever change, it makes no update of a job non-HOT.
- The dedup window is the job retention (jobs) and the event's retention (events); there is no
  retention of its own for keys. An event published with a retention of zero is never deduplicated.
- Keys are scoped to the job type, resp. the event name and key: keys taken from clients need the
  tenant or user in them.
- A mismatching duplicate (same key, different payload) is not detected; failing on it (e.g. a
  `DuplicateKeyException`) can be added later without a schema change.
