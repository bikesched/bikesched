---
title: "29. Child jobs as one step holding the outcome"
weight: 29
---

# 29. Child jobs as one step holding the outcome

Date: 2026-10-07

## Status

Accepted

## Context

A handler could not start another job exactly once (outside a step it is scheduled again on every
replay, inside one at least once) or wait for it to end without polling, and jobs had no result.
Waiting through an event published by the child misses children that fail, are cancelled or crash.
The design was discussed in `docs/specs/child-jobs.md`.

Alternatives considered:

- Separate start and wait steps (`JOB_START` + `JOB_WAIT`): a second name, kind and row per child
  without new information.
- The child's outcome as an internal event (`job-ended/<id>`): events expire after their retention,
  and failed or crashed children need the same end-of-job hook anyway.
- An envelope in `result` (`{"state", "result", "error"}`) instead of a `child_state` column: hides
  the state from SQL and mixes bikesched's format with the user's JSON.
- A result returned from the handler (`ResultJobHandler<T>`): a second handler type for every
  registration path; a flag fits `cancel()`/`fail()`.
- Throwing from `waitForJob` on a failed child: control flow by exception.
- `parent_job_id` as a foreign key, or a job kind column: deleting a parent would clear the
  reference, and a kind only repeats `recurrence` and `parent_job_id`.
- Deadlock detection: event waits don't declare who publishes, so the wait-for graph is unknown.

## Decision

- `Job.complete(result)` sets a job's result as a flag read after the handler; it is stored in
  `bikesched_jobs.result` only in the transaction ending the job `SUCCEEDED`.
- `Job.newJob(type)` returns a `StepJobBuilder` (no `connection(…)`, no `schedule(Recurrence)`, no
  `handler(…)`: children connect job types registered on their own, inline code is a step)
  that stores the child (`parent_job_id`) and a `CHILD_JOB` step of the parent in one transaction
  fenced by the parent's lock. The step is `WAITING` until the child ends; every terminal path of
  a child completes it in the child's transaction (`SUCCEEDED` with `child_state`, `result`,
  `last_error`) and makes a waiting parent due.
- `Job.waitForJob(childId, Class[, Duration])` reads that step (only the job's own children) and
  returns a sealed `JobResult` (`Succeeded`, `Failed`, `Cancelled`, `CompensationFailed`,
  `TimedOut`) or suspends the job without a deadline (or until the wait's stored deadline). A
  suspension at a child step locks the step row first, so it serializes with the child's end.
- No deadlock detection: job waits form a tree because a job only waits for its own children;
  cycles through events are bounded by wait timeouts.
- Occurrences of recurring jobs cannot start children, and children cannot be retried with
  `Bikesched.retry`.

## Consequences

- Fan-out with exactly-once starts and thread-free waits, and results visible through `findJob`.
- A child ending while its parent waits elsewhere causes one extra replay of the parent (accepted
  instead of storing where a job is suspended).
- Terminal writes of child jobs run in a transaction with two extra statements; other jobs are
  unchanged.
- Parent and child nodes must use compatible `JsonMapper`s.
- Follow-ups: results from `@JobHandler` return values and the Spring starter; cancellation
  propagation to children would need an index on `parent_job_id`.
