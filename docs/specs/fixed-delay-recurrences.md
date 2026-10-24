# Specification: fixed-delay recurrences

Status: **implemented** ([ADR 0058](../decisions/0058-fixed-delay-recurrences-and-planned-runs.md);
user docs: [Fixed delay](../jobs/recurring-jobs.md#fixed-delay),
[Annotations](../integrations/annotations.md)).

## 1. Goal

A recurring job whose runs take a varying share of their interval keeps the same gap after every
run, however long the run took (Spring's `fixedDelay`):

```java
bikesched.newJob("sync-crm")
    .handler(job -> crm.sync())
    .schedule(Recurrence.everyAfterEnd(Duration.ofMinutes(15)));

@JobHandler(type = "sync-crm", fixedDelay = "PT15M")
public void syncCrm() { … }
```

A run that ends at 12:10 is followed by the next one at 12:25, whether it started at 12:00 or at
12:09.

On the way, every occurrence stores its planned run, every recurrence computes from it and
`Recurrence.isFixedRate()` goes away: the only switch left on a recurrence is the fixed delay.

## 2. Today

The next occurrence is inserted when the previous one ends, so occurrences never overlap. What
the next run is computed from depends on `Recurrence.isFixedRate()`:

| Recurrence | Next run computed from | Stored |
|---|---|---|
| `every(Duration)` (`isFixedRate()` is `true`) | the previous occurrence's planned run | `planned_run_at` |
| `daily`, `weekly`, cron, `Recurrence.of` | the previous occurrence's scheduled time, which retries, sleeps, event waits and postponements move | nothing |

Runs in the past are skipped (`RecurringJob.nextRun(previous, now)`). With `every(PT15M)`, a run
starting at 12:00 and taking 10 minutes is followed by the next one at 12:15, 5 minutes after its
end; one taking 20 minutes by the next one at 12:30, 10 minutes after its end. The gap after a run
is anything between zero and the interval. A job that must leave a source (an API with a quota, a
replica) alone for a while after each run has no way to say so.

## 3. Scope

In scope:

- `planned_run_at` on every occurrence; the next run is computed from it for every recurrence
  that is not a fixed delay. `Recurrence.isFixedRate()` is removed (breaking, `refactor(job)!`).
- `Recurrence.isFixedDelay()` and `Recurrence.everyAfterEnd(Duration)`: the next run `interval`
  after the previous occurrence ended.
- `@JobHandler(fixedDelay = "PT15M")`, an ISO-8601 duration next to `schedule`; the Spring
  starter registers annotated beans through `JobHandlers`, so it gets it without a change.
- `@JobHandler(initialDelay = "PT0S")` for recurring handlers (`schedule` or `fixedDelay`): when a
  new first occurrence runs, like `schedule(recurrence, Duration delay)`. Needs
  `JobBuilder.schedule(String recurrence, Duration delay)` for `schedule` expressions.
- Kotlin: `everyAfterEnd(kotlin.time.Duration)` next to `every`.
- Docs (`recurring-jobs.md`, `annotations.md`, `spring.md`, `kotlin.md`, `database.md`),
  `AGENTS.md` (its `isFixedRate` example), an ADR.

Not in scope:

- A string form for `JobBuilder.schedule(String)` and the `RecurrenceParser`s: recurrence
  expressions stay what they are, code uses `Recurrence.everyAfterEnd`.
- Showing the planned next to the scheduled time in the admin UI (possible afterwards, see 4.5).
- A `Recurrence.of` overload for custom fixed delays: they implement the interface and override
  `isFixedDelay()`.
- Allowing occurrences to overlap (a fixed rate that starts a run while the previous one still
  runs).
- A minimum gap combined with a fixed rate (`max(planned + interval, end + gap)`).

## 4. Design

### 4.1 API

```java
// org.bikesched.job.Recurrence
/**
 * Returns the next run after the given one.
 *
 * @param previous the previous occurrence's planned run, or the time it ended if this is a
 *     {@linkplain #isFixedDelay() fixed delay}
 */
Instant next(Instant previous);

/**
 * Returns whether this is a fixed-delay recurrence: the next run is computed from the time the
 * previous occurrence ended instead of its planned run.
 *
 * @return {@code false} by default ({@link #everyAfterEnd(Duration)} returns {@code true})
 */
default boolean isFixedDelay() {
  return false;
}

/**
 * Runs {@code interval} after the previous occurrence ended (succeeded, failed or was
 * cancelled), however long it ran: a fixed delay.
 */
static Recurrence everyAfterEnd(Duration interval);
```

- `isFixedRate()` is removed: every recurrence that is not a fixed delay computes from the planned
  run. `every(Duration)` becomes a plain `Recurrence.of("every " + interval, …)`.
- `everyAfterEnd` rejects non-positive intervals like `every`. Its description is
  `every PT15M after end` (`"every " + interval + " after end"`), so switching a recurring job
  between `every` and `everyAfterEnd` moves the pending occurrence like any recurrence change.

### 4.2 Planned runs

Every occurrence gets the time it was planned for in `planned_run_at`, and `scheduled_at` starts
out equal to it:

- `RecurringJob.occurrence(at)` always sets it (`NewJob.plannedRunAt`, renamed from
  `fixedRateAt`); that covers registration, `RecurringJobRepair`, a recurrence change moving the
  pending occurrence (`RESCHEDULE_OCCURRENCE_SQL` already writes the column) and the next
  occurrence inserted when one ends.
- Retries, sleeps, event waits and postponements move `scheduled_at` only, as today for `every`.
- `RecurringJob.nextRun(ended, now)` computes from the planned run, or from `now` for a fixed
  delay. `JobOrigin.Occurrence(recurrence, plannedRunAt)` carries it as a non-null `Instant`.

What this changes for the recurrences that computed from the scheduled time:

| Recurrence | Change |
|---|---|
| `every` | none, it already works this way |
| `daily`, `weekly`, cron (also Spring's) | none: they snap to the wall clock, and runs in the past are skipped. A daily 03:00 run retried until 03:10 is followed by tomorrow 03:00 from either time; one waiting for an event until Wednesday by Thursday 03:00 |
| `Recurrence.of` adding to the previous run (`p -> p.plus(…)`) | keeps its rhythm instead of being pushed back by every retry or wait; "some time after the previous run" is now said with `everyAfterEnd` |

### 4.3 Fixed delay

- The end is whatever end inserts the next occurrence today: succeeded, permanently failed (after
  its failure handler ran) or cancelled, after a compensation if there is one. Retries, sleeps,
  event waits and postponements are part of the run: the occurrence stays pending and the delay
  starts once it really ends.
- The end time is the ending node's `Instant.now()` when the claim ends (`JobProcessor.end`), like
  every scheduled time a node computes; it is a few milliseconds before the row's `finished_at`.
- The first occurrence (registration, repair, a recurrence change) is due `interval` from now, like
  `every`; `schedule(recurrence, firstRun)` and `schedule(recurrence, delay)` work as before.
- No run is ever skipped: the next run is always in the future (`end + interval > now`).
- An occurrence that ends before its planned run (`Bikesched.cancel` on the pending occurrence)
  counts from its planned run instead, so cancelling skips exactly one run, as with a fixed rate.
  Pending at 12:15 and cancelled at 12:05, the next run is at 12:30, not at 12:20. An occurrence
  that ran (also one cancelled while waiting for a retry) ended after its planned run and counts
  from its end.
- An occurrence ending on a node that does not register the recurring job schedules no successor
  until a registering node runs its repair (today's behaviour for every recurrence); with a fixed
  delay the gap just gets longer.
- Whether the next run counts from the end is decided by the recurrence registered on the ending
  node, as `isFixedRate()` was: a changed recurrence changes its description, which moves the
  pending occurrence anyway.

```java
Instant nextRunAfterEnd(Instant planned, Instant ended) {
  Instant previous = recurrence.isFixedDelay() && ended.isAfter(planned) ? ended : planned;
  return nextRun(previous, ended);
}
```

### 4.4 Annotations

```java
// org.bikesched.annotation.JobHandler
/**
 * Optional fixed delay: an ISO-8601 duration (e.g. {@code PT15M}); the handler's job type becomes
 * a recurring job running this long after each occurrence ended ({@code
 * Recurrence.everyAfterEnd}). Not together with {@link #schedule()}.
 *
 * @return the delay, empty for none
 */
String fixedDelay() default "";

/**
 * Optional delay of a new first occurrence of a recurring handler ({@link #schedule()} or {@link
 * #fixedDelay()}): an ISO-8601 duration, e.g. {@code PT0S} to run right away. By default the
 * first occurrence is the recurrence's next run from now.
 *
 * @return the delay, empty for the default
 */
String initialDelay() default "";
```

- `JobHandlers.scan` parses the duration and rejects, with an `IllegalArgumentException` naming the
  method, an invalid or non-positive duration and a method setting both `schedule` and
  `fixedDelay`. Unlike `schedule`, which needs the node's parsers, it can check this when
  scanning.
- `AnnotatedJobHandler.fixedDelay()` returns an `Optional<Duration>` next to `schedule()`;
  `registerOn` registers the recurring job with
  `newJob(type).schedule(Recurrence.everyAfterEnd(delay))`.
- `initialDelay` maps to `schedule(recurrence, Duration delay)`. For `schedule` expressions this
  needs a new overload `JobBuilder.schedule(String recurrence, Duration delay)` (parsed like
  `schedule(String)`), next to the existing `schedule(Recurrence, Duration)`. `scan` rejects an
  invalid or negative duration and an `initialDelay` on a handler without `schedule` or
  `fixedDelay`; `AnnotatedJobHandler.initialDelay()` returns an `Optional<Duration>`.
- Unlike Spring's, the initial delay applies only when registering creates the first occurrence
  (or moves the pending one to a changed recurrence): registration is idempotent, so restarting a
  node keeps the pending occurrence where it is instead of running the job again after each
  start.
- The node's recurrence parsers and `schedule` stay as they are: `schedule = "PT15M"` keeps its
  fixed rate.
- `annotations.md` gets a paragraph next to the one on `schedule`; `spring.md` a line mapping
  Spring's `@Scheduled(fixedRate = …)` to `schedule` and `@Scheduled(fixedDelay = …)` to
  `fixedDelay` and `initialDelay` to `initialDelay`, and saying that without `initialDelay` the
  first run is one interval after registration, not right away as with Spring's default of 0.

### 4.5 Storage

Changed in `V001` in place (unreleased):

- The comment of `planned_run_at`: the planned run of an occurrence, not moved by retries or
  waits.
- `bikesched_jobs_planned_run_at_check` becomes `(planned_run_at is null) = (recurrence is
  null)`: every occurrence has one, no other job does.
- `database.md`: occurrences carry their `recurrence` and `planned_run_at` (no longer "for
  fixed-rate recurrences").

With the planned run on every occurrence, the admin UI could later show how far retries and waits
moved a run; not part of this change.

### 4.6 Kotlin

```kotlin
bikesched.createRecurringJob(type = "sync-crm", recurrence = everyAfterEnd(15.minutes)) {
  crm.sync()
}
```

`everyAfterEnd(interval: Duration): Recurrence` in `Durations.kt`, next to `every`.

## 5. Rejected alternatives

- **Keeping `isFixedRate()` next to a new `isFixedDelay()`**: two booleans with an invalid
  combination (both `true`), and a third behaviour (computing from the scheduled time) that only
  differs for additive custom recurrences, where drifting with retries is rather a bug.
- **An enum replacing `isFixedRate()` (`SCHEDULED`, `PLANNED`, `ENDED`)**: names all three
  behaviours, but `SCHEDULED` is not worth keeping (4.2), and with two left a boolean suffices.
- **A recurrence expression (`schedule = "PT15M after end"`)**: works wherever a recurrence string
  does, but mixes a second grammar into `RecurrenceParser.isoDuration()`; the attribute keeps the
  two concepts apart, as Spring does, at the cost of a check that the two attributes are not both
  set (when scanning, so still before anything is registered).
- **A separate `RecurrenceParser` for fixed delays**: it would have to be configured on every node
  (`recurrenceParsers(…)` replaces the set), and its `P…` expressions would overlap with
  `isoDuration()`, which must be the only parser supporting them.
- **The row's `finished_at` as the end time**: it would need a read after the end statement or a
  computed `scheduled_at` in SQL, while every other scheduled time is the node's clock. The
  difference is the end transaction's duration.
- **Naming it after Spring (`Recurrence.fixedDelay`)**: `every` would then need a `fixedRate`
  twin to read consistently, and "delay" already means the time to the first run
  (`schedule(recurrence, Duration delay)`).

## 6. Testing

- `RecurrenceTest`: `isFixedDelay()` of every built-in recurrence; `everyAfterEnd`'s description,
  `next`, rejected non-positive intervals.
- `RecurringJobNextRunTest`: a fixed delay computes from `now`, every other recurrence from the
  planned run.
- `RecurringJobTest` (integration):
  - `wallClockSchedulesStoreNoPlannedTime` becomes: a daily occurrence stores its planned run.
  - A `Recurrence.of` adding an hour, retried once: the next run is the planned run plus an hour
    (like the existing `every` test).
  - `everyAfterEnd(1s)`, the handler sleeping 1.5 seconds: the next occurrence is due about 1
    second after the previous one's `finished_at`; one failing once and retried, and one
    cancelled while waiting for its retry, both count from their end.
  - A pending `everyAfterEnd(1h)` occurrence cancelled before it ran: the next one is due at its
    planned run plus an hour.
  - Re-registering with `everyAfterEnd` moves a pending `every` occurrence.
- `SchemaMigratorTest` or a store test: the check rejects an occurrence without a planned run.
- `CronRecurrenceTest` asserts `isFixedDelay()` is `false` instead of `isFixedRate()`.
- Annotations: a `@JobHandler(fixedDelay = "PT1S")` method registers a recurring job with the
  `every PT1S after end` description; scanning rejects an invalid or non-positive duration and a
  method with both `schedule` and `fixedDelay`; `AnnotatedJobHandler.fixedDelay()`.
- Annotations: `initialDelay = "PT0S"` with `schedule` and with `fixedDelay` runs the first
  occurrence right away; a restart (registering again) keeps the pending occurrence; rejected
  without `schedule` or `fixedDelay` and with a negative duration.
- `JobBuilder.schedule(String, Duration)`: parsed like `schedule(String)`, first run after the
  delay.
- Spring starter: a bean with a `fixedDelay` method registers its recurring job.
- Kotlin: `everyAfterEnd(1.seconds)` in `createRecurringJob`.

## 7. Implementation steps

1. Planned run on every occurrence: `RecurringJob.occurrence`, `NewJob.plannedRunAt`,
   `JobOrigin.Occurrence`, the `V001` check and comment, `nextRun` from the planned run; remove
   `isFixedRate()` and update its users (`Recurrence.every`, `CronRecurrence` Javadoc, tests).
2. `isFixedDelay()`, `everyAfterEnd`, `nextRun` from `now`, the integration tests.
3. `@JobHandler.fixedDelay` and `initialDelay`, `AnnotatedJobHandler.fixedDelay()` and
   `initialDelay()`, `JobBuilder.schedule(String, Duration)`, the annotations and Spring tests.
4. Kotlin helper and test.
5. Docs (`recurring-jobs.md`: "Recurrences" with the planned run and a fixed-delay part,
   `annotations.md`, `spring.md`, `kotlin.md`, `database.md`),
   `AGENTS.md`, ADR, and the `IDEAS.md` entry removed.

## 8. Open questions

None.

## 9. Decisions

1. Every occurrence stores its planned run and every recurrence but a fixed delay computes from
   it; `isFixedRate()` is removed (4.2). Computing from the scheduled time differed only for
   additive custom recurrences.
2. A fixed delay is a boolean `isFixedDelay()` on `Recurrence`, decided by the recurrence
   registered on the ending node (4.3).
3. The end time is the ending node's clock (5).
4. Annotations get a separate `fixedDelay` attribute; recurrence expressions get no fixed-delay
   form, `schedule = "PT15M"` stays fixed rate (4.4).
5. The factory is `Recurrence.everyAfterEnd(Duration)`, description `every PT15M after end`: "end"
   says that a failed or cancelled run starts the delay too, unlike `everyAfterCompletion`.
6. `@JobHandler.initialDelay` delays a new first occurrence of a recurring handler, like
   `schedule(recurrence, Duration delay)`; it does not apply again on every start (4.4).
7. A fixed-delay occurrence cancelled before its handler ever ran counts from its planned run, so
   cancelling the pending run skips one run instead of restarting the delay (4.3).

## 10. Decided while implementing

1. "Cancelled before its handler ever ran" (decision 7) is implemented as "ended before its planned
   run" (4.3, `RecurringJob.nextRunAfterEnd`): attempt counts cannot tell, because suspensions and
   postponements give their attempt back, and a pending occurrence cancelled before it is due is
   exactly the case that ends before its planned run.
2. Kotlin also gets `JobBuilder.schedule(String, kotlin.time.Duration)` for the new Java overload,
   like the other `kotlin.time.Duration` overloads.
3. The Spring starter needed no change: it registers `@JobHandler` beans through
   `AnnotatedJobHandler.registerOn`.

