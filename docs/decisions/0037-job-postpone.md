---
title: "37. Postponing a job gives its attempt back"
weight: 37
---

# 37. Postponing a job gives its attempt back

Date: 2026-10-11

## Status

Accepted.

## Context

Rate and concurrency limits belong to libraries such as Bucket4j and Resilience4j, which know the
limit and how long to wait. When such a library says "not now", a handler could only fail the
attempt: `job.fail(…).retryAfter(…)` uses up an attempt, stores `last_error` and records a failure,
so a busy provider ends jobs `FAILED` that never failed. bikesched needs a way to end an attempt
for later that is no failure. The check usually sits in the step calling the provider, so the
mechanism has to work inside steps.

## Decision

- `job.postpone(Duration)` ends the attempt by throwing the internal suspension (`JobSuspended`)
  that sleeps and waits use, so it never returns and a step cannot store a result after it. A
  handler catching it is still postponed.
- The job becomes `SCHEDULED`, due after the delay, with its attempt given back and `last_error`
  untouched; the node postponing it wakes itself up (`dueAt`), like a retry. A cancellation
  requested meanwhile makes it due right away, so the cancellation applies.
- A step whose handler postponed the job gets the new state `POSTPONED`, keeps no result and gets
  its attempt back; the next attempt starts it again with the same `Step.attempts()`.
- The job history mirrors the states: the job shows `SCHEDULED` with `postponed [in step '…'] until
  …`, the step `POSTPONED`. Job listeners get `JobAttemptOutcome.POSTPONED`.
- It is rejected in compensations, step failure handlers and poll checks (`Poll.notReady()` covers
  "not yet" there), and does nothing while replaying for compensation. There is no limit on how
  often a job is postponed; the handler fails it to give up.

Rejected alternatives:

- **A failed step with its attempt given back** instead of `POSTPONED`: reuses the restart path,
  but every query and metric on `FAILED` would count a postponement.
- **A `WAITING` job**, like a sleep: `WAITING` means "suspended at a step", which a postponement
  outside a step is not, and a postponed job must be claimable like any due job.
- **Concurrency limits in the claim query** (e.g. at most two running jobs of a type): useful, but
  a separate feature; postponing covers rate limits and per-JVM limits now.

## Consequences

- Rate-limited jobs keep their attempts for real failures; Bucket4j's `getNanosToWaitForRefill()`
  or a provider's `Retry-After` maps directly onto the delay.
- Every postponement costs one claim and two history entries (three in a step); a job postponed
  very often grows its history until the retention removes it.
- `bikesched_steps` has one more state; code paths that look at step states ignore `POSTPONED`
  like any state other than `SUCCEEDED`, so the step simply runs again.
