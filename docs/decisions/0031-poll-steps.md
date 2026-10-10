---
title: "31. Poll steps: one step for all checks"
weight: 31
---

# 31. Poll steps: one step for all checks

Date: 2026-10-08

## Status

Accepted; the timeout handler is superseded by
[ADR 0034](0034-wait-timeouts-as-failures.md).

## Context

Jobs often have to wait for an external service that has no callback, or whose callback may get
lost: a report being generated, a payment settling. With steps, sleeps and failures alone,
polling had a drawback in every form: failing the attempt while the result is not ready
(`job.fail(…).retryAfter(…)`) uses up attempts, records failures and ends `FAILED` with
compensation; a loop of numbered steps and sleeps adds two rows per check and replays every
earlier check on each wake-up (quadratic queries); a loop inside one step holds a concurrency slot
and the attempt for the whole time; and a step cannot return "not ready", because whatever it
returns is stored for good.

Alternatives considered: an option on `newStep` (the step handler would have to return something
meaning "not ready", unlike every other step), `Optional<T>` as the check's result (no per-check
delay, no `null` value), exceptions as "not ready" (would hide real errors), a default interval
(none fits every service), a separate `backoff(…)` builder method next to the interval (mutually
exclusive options, settled only at runtime), storing the deadline in `wait_until` (the claim and
the suspension make jobs due at `wait_until`, so the next check has to stay there), a history entry
per check, and skipping only the job's suspension entries (the history would show the job running
while it waited without a lock).

## Decision

bikesched polls with a step of its own kind, `POLL`: `job.newPoll(name, type, interval)` with a
required interval (a `Duration`, or a `RetryBackoff` called with the number of each check that was
not ready), an optional `timeout(…)` with or without a fallback, and `run(handler)`. The handler
returns a sealed `Poll<T>`: `Poll.ready(value)` completes the step and stores the value,
`Poll.notReady()` (optionally with the time until the next check) suspends the job until the next
check, reusing the suspension of sleeps. An exception fails the attempt as in any step.

- One row for all checks: `attempts` counts them, `wait_until` is the next check (capped at the
  deadline, so the last check runs at the deadline), the new column `timeout_at` is the deadline,
  stored on the first check and kept on later ones.
- A timeout without a fallback fails the job permanently with a `PollTimeoutException`, like an
  event wait's default timeout; the fallback is not stored and runs on every replay.
- The job history records a poll's first suspension and its outcome. Claims of jobs whose poll is
  waiting, and their suspensions, are left out; the claim finds them with one query per claimed
  batch, only with the history enabled.
- Compensation replay returns a ready poll's value and a timed-out poll's fallback, so the
  compensations of later steps register; an unfinished poll ends the replay.

## Consequences

- Polling costs one row and no attempts; checks that are not ready end their attempt like a sleep
  (listener outcome `WAITING`).
- A history reader sees no entry per check: the poll step's `attempts` holds the count. The job's
  return from `WAITING` after its last check has no entry of its own; the poll's outcome stands for
  it.
- Checks run up to one poll interval late, like sleeps; frequent checks belong into a loop inside a
  step.
- A combined wait ("the webhook or the next check, whichever comes first") is left out: an event
  wait with a timeout followed by a poll covers webhooks with a polling fallback.
