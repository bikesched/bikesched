---
title: "57. Fixed-delay recurrences and a planned run on every occurrence"
weight: 57
---

# 57. Fixed-delay recurrences and a planned run on every occurrence

Date: 2026-10-23

## Status

Accepted.

## Context

A recurring job's next occurrence is inserted when the previous one ends. `Recurrence.every`
computed it from the previous occurrence's planned run (a fixed rate, flagged by
`Recurrence.isFixedRate()` and stored in `planned_run_at`), every other recurrence from its
scheduled time, which retries and waits move. Neither kept a gap after a run: a sync taking 10 of
its 15 minutes left 5 minutes to the next run. Spring calls the missing mode `fixedDelay`.

Adding a fixed delay next to `isFixedRate()` would have given two booleans with an invalid
combination, or an enum with three modes. The third mode, computing from the scheduled time,
only differs from the planned run for custom recurrences that add to the previous run, where
drifting with every retry is rather a bug; wall-clock recurrences (daily, weekly, cron) land on
the same run either way, because runs in the past are skipped.

For annotations, a recurrence expression (`schedule = "PT15M after end"`) would have worked
wherever a recurrence string does, but mixes a second grammar into the ISO-8601 duration parser.

## Decision

- Every occurrence stores its planned run (`planned_run_at`, now set exactly for occurrences),
  and the next run is computed from it. `Recurrence.isFixedRate()` is removed.
- `Recurrence.isFixedDelay()` (default `false`) makes the next run count from the end of the
  previous occurrence instead; `Recurrence.everyAfterEnd(Duration)` (description `every PT15M
  after end`) is the built-in fixed delay. The end time is the ending node's clock.
- An occurrence ending before its planned run (cancelled while pending) counts from its planned
  run, so cancelling the pending occurrence skips one run for every recurrence.
- `@JobHandler` gets `fixedDelay` (an ISO-8601 duration, not together with `schedule`) and
  `initialDelay` (the delay of a new first occurrence of a recurring handler), checked when
  scanning. `JobBuilder.schedule(String, Duration)` backs `initialDelay` for expressions.
  Recurrence expressions get no fixed-delay form.

## Consequences

- Retries and waits no longer shift custom recurrences that add to the previous run; a gap
  after each run is said with `everyAfterEnd`.
- One more timestamp per occurrence; the admin UI could show how far an occurrence was moved.
- Spring users get the familiar attributes. Unlike Spring's, `initialDelay` only applies to a new
  first occurrence, since registration is idempotent across restarts.
