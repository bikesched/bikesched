# 26. Durable sleep as a step without an event

Date: 2026-10-07

## Status

Accepted

## Context

Jobs can wait for an event without holding a thread, but not for time: a handler pausing for days
(a trial period, a reminder before a booking) had to split itself into several jobs or wait for an
event nobody publishes. Like every durable operation, a sleep must be recognized when the handler
runs again from the top. The design was discussed in `docs/specs/job-sleep.md`.

Alternatives considered:

- `job.sleep(duration)` without a name, identified by a counter or the call site: a deploy that adds
  or removes a sleep shifts the identities, so running jobs wake from the wrong sleep.
- A job state `SLEEPING`: every query, constraint and index on job states gains a case for the same
  behaviour as `WAITING`.
- A separate timer table: duplicates what a step already has (name, state, replay).
- Sleeping in the thread for short durations: holds a slot and is lost on a crash.
- An event wait with a timeout and no event: leaks `TIMED_OUT` and timeout handlers into sleeping.

## Decision

`Job.sleep(stepName, Duration)` and `Job.sleepUntil(stepName, Instant)` store a step without an
event: `WAITING` with `wait_until` as the wake-up time (computed by the database for `sleep`), or
`SUCCEEDED` right away for a passed time. The handler's attempt ends as for an event wait; the job
is `WAITING` with `scheduled_at` = the wake-up time and is claimed like any due waiting job. The
next run completes the step once the database's `now()` has passed the wake-up time, otherwise it
suspends again. The stored wake-up time wins over a changed argument; both methods return `void`.

The step check constraint allows `WAITING` without an event when `wait_until` is set; `TIMED_OUT`
stays event-only. Internally, the suspension is a sealed type (`EventSuspension`,
`SleepSuspension`): an event suspension takes the event's advisory lock, a sleep does not.

## Consequences

- No new table, column, state or index; cancellation, compensation replay, history, attempts and
  listeners treat a sleep like an event wait.
- A job wakes up to one poll interval late.
- `WAITING` jobs are now either waiting for an event or sleeping; the step tells which.
