---
title: "61. References for jobs and events"
weight: 61
---

# 61. References for jobs and events

Date: 2026-10-25

## Status

Accepted

## Context

Jobs and events are usually about an entity of the application: an order, a payment, a webhook
delivery. Operators looking for "the jobs of order 4711" could only guess the job name and open
payloads one by one; the admin UI searched by id only. The idempotency key often holds such an id,
but it deduplicates per job name, so it cannot tag several jobs for one order, and the event key is
the correlation key of waiting steps.

Alternatives considered: searching inside payloads (`jsonb` paths or a GIN index: needs the
payload shape per name, indexes every payload, and the admin deliberately never reads payloads for
its lists), a separate table of references per job or event (many references per row, but an extra
insert, join and clean-up for a value most rows have once or not at all), a full index on the
column (indexes every row, most of them without a reference), and several references per row
(`text[]` with a GIN index), kept for later should one value prove too narrow.

## Decision

- An optional `reference(String)` on every job builder (`JobSettings`, so also transactional,
  idempotent, recurring and child jobs) and every event builder (`EventBuilder`). 1 to 200
  characters like names (`ValidationUtil.requireName`, `char_length` and `octet_length` CHECKs),
  not unique, set once and never changed; `reference(null)` sets none.
- Stored in `bikesched_jobs.reference` and `bikesched_events.reference`, with the partial indexes
  `(reference, id) where reference is not null`: rows without a reference cost a null bit and no
  index entry, and the column never changes, so updates of jobs stay HOT for this index.
- Child jobs and events published by a job inherit the job's reference unless their builder sets
  one, so one search finds the whole tree. The value is resolved when the child or event is
  created inside its step, so a replay changes nothing.
- Read back through `JobInfo`, `Job` and `FailedJob` (`Optional<String> reference()`), the admin
  summaries, the logging context (`bikesched.jobReference`, only for jobs with a reference) and the
  listener records `JobClaim` and `EventPublication`. The job history names it in the `SCHEDULED`
  detail, like the idempotency key; there is no history column.
- Lookups: `JobQuery.withReference` and `EventQuery.withReference`, exact match, combinable with
  the other filters. The admin UI's search field matches ids (8 to 32 hex digits) and references at
  once.

## Consequences

- Operators find all jobs and events of an entity in one search, without payloads.
- References are visible to every admin user and land in logs: the docs ask for opaque ids, not
  names or email addresses. `bikesched-micrometer` does not tag meters with them (unbounded
  cardinality).
- A recurring job's reference is the same for every occurrence; allowed for uniformity.
- Prefix or case-insensitive search would need another index (`text_pattern_ops` or an
  expression index); not done.
