# Recurring jobs

A recurring job is a job type with a schedule. Every node registers it on startup; bikesched keeps
exactly one run pending at a time.

```java
bikesched.newJob("cleanup")
        .recurring(Schedule.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")))
        .handler(job -> cleanup())
        .schedule();   // idempotent: every node registers it on startup
```

## Schedules

- `Schedule.every(Duration)`, `Schedule.daily(LocalTime, ZoneId)`,
  `Schedule.weekly(DayOfWeek, LocalTime, ZoneId)`, or your own via
  `Schedule.of(description, previous -> next)`.
- The next run is computed from the previous occurrence's scheduled time. Schedules whose
  `isFixedRate()` returns `true` (by default only `every`) also store each occurrence's planned
  time (`fixed_rate_at`) and compute from that instead, so retries and event waits don't shift a
  fixed rate.
- Each occurrence stores the schedule's description. Registering a schedule with a different
  description (e.g. after a deploy) moves the pending occurrence to the new schedule.

## Occurrences

- The job type identifies the recurring job. Each run is an *occurrence*, a regular job with its
  own retries, timeout, steps and events. At most one occurrence is pending at a time, enforced by
  a partial unique index.
- When an occurrence ends (succeeded, permanently failed, or cancelled, which cancels only that
  occurrence), the next one is inserted in the same transaction. Runs missed while no node was up
  are skipped.
- Registering a recurring job recreates a missing occurrence; a node also checks all its recurring
  jobs once when it starts polling, retried every `nodeCleanUpInterval` if that fails.
- `bikesched.removeRecurringJob(type)` stops a recurring job that is no longer registered anywhere.
- `recurring(...)` and `connection(...)` lead to different builders, so a recurring job can't be
  scheduled in the caller's transaction.

---

Next: [Events](events.md) · Previous: [Steps and compensation](steps.md) ·
[Documentation index](index.md)
