---
title: "Recurring jobs"
weight: 1
---

# Recurring jobs

A recurring job is a job name with a `Recurrence`. Every node registers it on startup; bikesched
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
| `schedule(String recurrence)` | like `schedule(recurrence)`, parsed from an expression, see [Recurrence expressions](#recurrence-expressions) |
| `schedule(String recurrence, Duration delay)` | once `delay` has passed, the recurrence parsed from an expression |

Unlike the one-off `schedule` calls, registering a recurring job is **idempotent**: it only creates
the first occurrence if none is pending, so every node can run the same code on every start. It
returns the id of the pending occurrence.

## Recurrences

- `Recurrence.every(Duration)` (a fixed rate), `Recurrence.everyAfterEnd(Duration)` (a
  [fixed delay](#fixed-delay)), `Recurrence.daily(LocalTime, ZoneId)`,
  `Recurrence.weekly(DayOfWeek, LocalTime, ZoneId)`, or your own via
  `Recurrence.of(description, previous -> next)`.
- Each occurrence stores the time it was planned for (`planned_run_at`), and the next run is
  computed from it, so retries, sleeps and event waits that move an occurrence don't shift the
  ones after it. Runs whose time has passed when an occurrence ends are skipped: `every(PT15M)`
  started at 12:00 and running for 20 minutes is followed by the run at 12:30.
- Recurrences that snap to the wall clock (`daily`, `weekly`, cron) return `true` from
  `isWallClock()`: after missed runs, the next run is computed from the current time in one step.
  A custom recurrence that returns the first match after any instant can do the same.
- Each occurrence stores the recurrence's description. Registering a recurrence with a different
  description (e.g. after a deploy) moves the pending occurrence to the new recurrence.

## Fixed delay

`Recurrence.everyAfterEnd(Duration)` computes the next run from the time the previous occurrence
ended instead of its planned run, like Spring's `fixedDelay`. Every run is followed by the same
gap, however long it took:

```java
bikesched.newJob("sync-crm")
        .handler(job -> crm.sync())
        .schedule(Recurrence.everyAfterEnd(Duration.ofMinutes(15)));
```

- A run ending at 12:10 is followed by the next one at 12:25, whether it started at 12:00 or at
  12:09. No run is skipped.
- The end is whatever ends the occurrence: it succeeded, failed permanently (after its failure
  handler) or was cancelled, after its compensations if it has any. Retries, sleeps, event waits
  and postponements belong to the run.
- Cancelling the pending occurrence before its planned run skips one run: the next one is
  computed from the cancelled occurrence's planned run, as with a fixed rate.
- The description is `every PT15M after end`. The first occurrence runs one interval after
  registration, or at the `firstRun` or after the `delay` given to `schedule`.
- With annotations, declare it with `@JobHandler(fixedDelay = "PT15M")`, see
  [Annotations](../integrations/annotations.md).

## Recurrence expressions

`schedule(String)` and `@JobHandler(schedule = …)` take a recurrence as a string, parsed by the
node's `RecurrenceParser`s:

```java
bikesched.newJob("cleanup").handler(job -> cleanup()).schedule("PT1H");
```

- By default a node has one parser, `RecurrenceParser.isoDuration()`: ISO-8601 durations
  (`PT1H`), run like `Recurrence.every(Duration)` at a fixed rate.
- `BikeschedBuilder.recurrenceParsers(…)` replaces the set; list `isoDuration()` as well to keep
  durations. A parser is a `supports(expression)` check plus `parse(expression)`; an expression
  must be supported by exactly one parser, otherwise `schedule(String)` throws an
  `IllegalArgumentException`, as it does for an invalid expression.
- Expressions suit schedules from configuration; in code, a `Recurrence` is checked by the
  compiler.

## Cron expressions

The module `bikesched-cron` adds `CronRecurrence`, parsed with
[cron-utils](https://github.com/jmrozanec/cron-utils) and evaluated in a time zone:

```java
bikesched.newJob("report")
        .handler(job -> sendReport())
        .schedule(CronRecurrence.of("0 3 * * MON-FRI", ZoneId.of("Europe/Berlin")));
```

- `CronRecurrence.of(expression, zone)` takes a Unix expression (five fields, minute precision);
  `CronRecurrence.of(expression, CronType, zone)` another cron-utils dialect, e.g.
  `CronType.QUARTZ` or `CronType.SPRING53` with a seconds field.
- Invalid expressions and expressions without a future execution (`0 0 30 2 *`) are rejected with
  an `IllegalArgumentException` when the recurrence is created.
- The description is the dialect, the normalized expression and the zone
  (`cron unix 0 3 * * 1-5 Europe/Berlin`), so changing any of them moves the pending occurrence.
  Cron snaps to the wall clock.
- Daylight saving time: a local time that doesn't exist on a day (clocks moving forward) is skipped
  that day — unlike `Recurrence.daily`, which shifts it. A local time that occurs twice (clocks
  moving back) runs once if the expression names the hour (`30 2 * * *`) and in both passes if the
  hour is a wildcard (`*/30 * * * *`).
- `CronRecurrenceParser` parses cron expressions given as strings, in its dialect (Unix by
  default) and zone (UTC by default):

  ```java
  Bikesched.builder()
          .recurrenceParsers(RecurrenceParser.isoDuration(),
                  new CronRecurrenceParser(CronType.UNIX, ZoneId.of("Europe/Berlin")))
          …
  bikesched.newJob("report").handler(job -> sendReport()).schedule("0 3 * * MON-FRI");
  ```

## Occurrences

- The job name identifies the recurring job. Each run is an *occurrence*, a regular job of type
  `RECURRING` with its own retries, timeout, steps and events. At most one occurrence is pending at
  a time, enforced by a partial unique index.
- When an occurrence ends (succeeded, permanently failed, or cancelled, which cancels only that
  occurrence), the next one is inserted in the same transaction. Runs missed while no node was up
  are skipped.
- Registering a recurring job recreates a missing occurrence; a node also checks all its recurring
  jobs once when it starts polling, retried every `nodeCleanUpInterval` if that fails.
- `bikesched.removeRecurringJob(name)` stops a recurring job that is no longer registered anywhere.
- `connection(...)` leads to a builder without `schedule(Recurrence)`, so a recurring job can't be
  scheduled in the caller's transaction.
