# 9. Step writes fenced by the job's lock

Date: 2026-10-05

## Status

Accepted

## Context

A node that misses heartbeats (GC pause, network partition) may keep running a job that another
node has released and claimed meanwhile. Both could record results for the same steps.

## Decision

Every step write and event wait only takes effect while the job is still `RUNNING` under the
writing attempt's lock, checked in the same statement, which share-locks the job row so a
concurrent release waits for it. A running step also records its node (`locked_by`). A write that
finds the lock lost throws `JobLockLostException`; the node then abandons the attempt without
recording an outcome, even if the handler caught the exception.

## Consequences

- The database state stays consistent; side effects outside it can still happen twice
  (at-least-once, see idempotency keys in `docs/steps.md`).
- Work outside steps is not fenced.
