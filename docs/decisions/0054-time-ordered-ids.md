---
title: "54. Time-ordered ids (UUIDv7)"
weight: 54
---

# 54. Time-ordered ids (UUIDv7)

Date: 2026-10-23

## Status

Accepted

## Context

Job, event and node ids were random UUIDs (version 4). Every insert into `bikesched_jobs`,
`bikesched_events` and the tables whose primary key starts with a job id (`bikesched_steps`,
`bikesched_job_history`, the largest table) went to a random leaf page of the index: the whole
index is the working set, with more page splits, more full-page writes after a checkpoint and
worse cache hit rates as the tables grow.

The admin's job list paged by `created_at` and needed its own index, `(created_at, id)`, on the
most frequently written table. Job updates are almost never HOT (`state` is in the predicates of
the partial indexes), so every state change added an entry to that index too.

The admin showed the first 8 hex digits of an id as its short id and searched by that prefix on
the primary key, which relied on the ids being random.

## Decision

bikesched creates ids as UUIDs of version 7 (RFC 9562) in the library (`UuidUtil`): 48 bits of
Unix milliseconds, a 12-bit counter that keeps the ids of one JVM strictly increasing within a
millisecond, and 62 random bits. They are created in Java, not with PostgreSQL 18's `uuidv7()`,
so the id is known before the insert, as before, and no PostgreSQL version is required.

- The admin's job and event lists page by id (`JobQuery` and `EventQuery` have `beforeId`
  only): the primary keys serve the lists of all jobs and all events,
  `bikesched_jobs_type_id_idx (type, id)` the list of one job type.
  `bikesched_jobs_created_at_idx` and `bikesched_events_published_at_idx` are gone.
- The short id is the last 8 hex digits, the random part. `Admin.findJobsByIdSuffix` and
  `findEventsByIdSuffix` replace the prefix searches; an expression index on
  `right(id::text, 8)` per table finds them.

Rejected: keeping prefix search with UUIDv7 (every job of the same ~65 seconds shares its first 8
digits, so a unique prefix needs about 20 digits), and staying with random ids.

## Consequences

- New rows land at the end of the primary keys and of the indexes led by a job id.
- The lists' order is the order the ids were generated on the application nodes; clock skew
  between nodes can swap jobs created within a few milliseconds of each other.
- The admin's API cursor (`next`, `before`) is now the last id of a page instead of time and id.
- An id reveals its creation time.
- `bikesched_jobs` and `bikesched_events` keep the same number of indexes (an admin index
  replaced by the narrower short id index), but their primary keys no longer receive random
  inserts.
