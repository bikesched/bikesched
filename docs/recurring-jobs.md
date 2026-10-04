# Recurring jobs

A recurring job is a job type with a `Recurrence`. Every node registers it on startup; bikesched
keeps exactly one run pending at a time. It is scheduled like a one-off job, with a `Recurrence`
instead of a time:

```java
bikesched.newJob("cleanup")
        .handler(job -> cleanup())
        .schedule(Recurrence.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")));
```

| Call | First occurrence |
|---|---|
| `schedule(recurrence)` | the recurrence's next run from now |
| `schedule(recurrence, Instant firstRun)` | at `firstRun` |
| `schedule(recurrence, Duration delay)` | once `delay` has passed |

Unlike the one-off `schedule` calls, registering a recurring job is **idempotent**: it only creates
the first occurrence if none is pending, so every node can run the same code on every start. It
returns the id of the pending occurrence.

## Recurrences

- `Recurrence.every(Duration)`, `Recurrence.daily(LocalTime, ZoneId)`,
  `Recurrence.weekly(DayOfWeek, LocalTime, ZoneId)`, or your own via
  `Recurrence.of(description, previous -> next)`.
- The next run is computed from the previous occurrence's scheduled time. Recurrences whose
  `isFixedRate()` returns `true` (by default only `every`) also store each occurrence's planned
  time (`fixed_rate_at`) and compute from that instead, so retries and event waits don't shift a
  fixed rate.
- Each occurrence stores the recurrence's description. Registering a recurrence with a different
  description (e.g. after a deploy) moves the pending occurrence to the new recurrence.

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
- `connection(...)` leads to a builder without `schedule(Recurrence)`, so a recurring job can't be
  scheduled in the caller's transaction.

---

Next: [Events](events.md) · Previous: [Steps and compensation](steps.md) ·
[Documentation index](index.md)
