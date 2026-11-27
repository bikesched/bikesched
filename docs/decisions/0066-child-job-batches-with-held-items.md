---
title: "66. Child job batches with held items"
weight: 66
---

# 66. Child job batches with held items

Date: 2026-11-27

## Status

Accepted

## Context

Fan-out with child jobs ([ADR 0029](0029-child-jobs-as-steps.md)) starts each child in a step
transaction of its own and waits for them one by one. A child's end makes a waiting parent due
whatever it waits for, so N children cost N transactions to start and up to N replays of the
parent, each reading all N steps. Nothing limits how many children of one parent run at once,
and the loops, step names and outcome handling are written by hand.

Alternatives considered:

- Parallel steps inside the parent's attempt: no job rows, but every item runs on the node that
  claimed the parent, shares its attempt timeout and cannot retry on its own.
- `waitForJobs(ids)` over arbitrary children: no name to find the wait on a replay, nothing for
  the admin UI to show.
- Parallelism as a filter in the claim query: every claim of every job pays for a subquery.
- Held items as `SCHEDULED` with `scheduled_at = 'infinity'`: no new state, but they look like
  jobs scheduled for never.
- Items publishing events the parent waits for: a key per item, and the item's error is lost.

## Decision

bikesched starts a batch of child jobs as one durable step of its parent
(`job.newJobBatch(stepName, job)…schedule()`), in one transaction fenced by the parent's lock:
a `CHILD_JOB_BATCH` step with the item count, parallelism and mode, every item as a `CHILD` job
with its batch step, position and key (`batch_step_id`, `batch_index`, `batch_key`), and a
`CHILD_JOB` step per item named `stepName/key`. Later runs return the stored batch.

- **Parallelism**: with `parallelism(k)`, items beyond the first `k` are stored in the new job
  state `HELD`, which the claim query never takes. The batch step counts its active and ended
  items (`batch_active_count`, `batch_ended_count`); an item's end admits as many held items as
  the counters allow, in item order, with `for update skip locked`.
- **Resume once**: an item's end locks the batch step, completes its item step and the counters,
  and only the last item resumes the parent. `JobBatch.await()` suspends at the batch step and
  returns a `JobBatchResult` of every item's `JobResult`. Waiting for a single item is rejected.
- **Fail-fast** (`failFast()`): the first item that does not succeed ends the batch `FAILED`,
  resumes the parent and cancels the siblings in the same transaction: held items directly,
  scheduled and running ones by a cancel request, all with `skip locked`. Siblings skipped as
  locked get their cancel request right after the commit, in a transaction of their own.
- **Lock order**: the ending item's row, the batch step, item steps by id, other items (`skip
  locked` only), the parent's row. Every write of a batch follows it.
- **Cancellation and ends**: a timeout, the parent's cancellation, compensation and end without
  waiting cancel the held items; running items keep running. Compensation runs per admitted item
  in reverse order; a compensated batch restarts with the same keys.
- **Size**: `maxJobBatchSize` (default 1,000) bounds a batch; items are inserted with `unnest` in
  chunks of 1,000.

## Consequences

- A batch of N items costs one transaction to start, one per item end and one resume of the
  parent; the parallelism is exact across nodes without touching the claim query.
- A new job state (`HELD`) and step type (`CHILD_JOB_BATCH`) that code matching on states and the
  admin UI handle; the admin lists a batch as one step and its items on demand.
- The first transaction grows with the batch: raising `maxJobBatchSize` needs a parent timeout that
  allows for the insert.
- Not covered: batches started by the application outside a job, adding items to a started batch,
  and waiting for single items before the batch ended.
