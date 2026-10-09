# Specification: durable sleep in jobs (`job.sleep`, `job.sleepUntil`)

Status: **implemented** (core and `bikesched-kotlin`, ADR 0026). User docs: `docs/steps.md#sleeping`.

## 1. Goal

A job handler can pause until a duration has passed or a point in time is reached, durably and
without holding a thread or a concurrency slot, the same way it already waits for an event:

```java
bikesched.registerJobHandler("trial", job -> {
    Account account = job.payload(Account.class);
    job.step("send-welcome", () -> mail.welcome(account));
    job.sleep("trial-period", Duration.ofDays(14));
    job.step("send-trial-ended", () -> mail.trialEnded(account));
});

job.sleepUntil("until-check-in", booking.checkIn().minus(Duration.ofDays(1)));
```

The attempt ends at the sleep, the job is `WAITING` until the wake-up time, and its handler then
runs again from the top: completed steps are skipped, the sleep returns, the handler continues.

## 2. Non-goals

- Precise wake-ups: a sleeping job becomes due at its wake-up time and is claimed on the next poll,
  so it wakes up to one `pollInterval` late (5 seconds by default). Sub-second sleeps belong in the
  handler (`Thread.sleep`), not in a durable sleep.
- Waking a sleep early (an "interrupt" or a combined "event or time" wait): an event wait with a
  timeout already covers "until the event, at most this long".
- Calendar logic ("next business day"): callers compute the `Instant` and use `sleepUntil`.
- Sleeping in compensations and step handlers: rejected like event waits (§4.6).

## 3. Module and dependencies

Core only (`org.bikesched.job.Job`, `internal.job`, `internal.database`, `V001`), plus Kotlin
extensions with `kotlin.time.Duration` in `bikesched-kotlin`. No new dependencies.

## 4. Design

### 4.1 API

On `Job`, next to `waitForEvent`:

```java
/** Sleeps as a durable step until {@code duration} after the step first ran. */
void sleep(String stepName, Duration duration);

/** Sleeps as a durable step until {@code time}; returns right away if it has passed. */
void sleepUntil(String stepName, Instant time);
```

- `duration` must be positive; `time` may be in the past (the step then completes right away, see
  §4.3).
- Both return `void`. The wake-up time is stored with the step (`wait_until`).
- Kotlin: `job.sleep(stepName, 14.days)` as an extension taking `kotlin.time.Duration` (a different
  parameter type, so no clash with the member); `sleepUntil` needs no extension.

**Step name, required (proposed) vs. not.** The idea was `job.sleep(duration)` without a name.
Every durable operation of a job (steps, event waits) is identified by a unique step name, because
the handler runs again from the top and bikesched has to recognize the sleep on the next run:

| Option | Identification on replay | Problem |
|---|---|---|
| A: `sleep(stepName, duration)` (proposed) | by name, like steps and waits | one more argument |
| B: `sleep(duration)`, name generated from a counter (`sleep-1`, `sleep-2`) | by position among the job's sleeps | inserting or removing a sleep in a deploy shifts the names: a running job wakes from the wrong sleep or sleeps again |
| C: `sleep(duration)`, name from the call site (stack trace) | by class/line | moves with every edit of the file; expensive |

Recommendation: A, consistent with `step` and `waitForEvent`, and safe across deploys.

### 4.2 Storage

A sleep is a step without an event: `bikesched_steps` row with `event_name`/`event_key` null,
`state = 'WAITING'` and `wait_until` = the wake-up time. No new table, no new column, no new state.

- `sleep(duration)`: `wait_until = now() + duration`, computed by the database (like an event
  wait's deadline), so node clocks don't matter.
- `sleepUntil(time)`: `wait_until = time`.
- The stored `wait_until` wins on later runs: a handler computing `Instant.now().plus(…)` outside a
  step passes a different argument on every run, and the sleep must not move. A changed argument
  is therefore ignored (documented, like a changed step result).

The step check constraint changes in place (unreleased, `V001`):

```sql
-- today
check ((event_name is null) = (event_key is null)
       and (event_name is not null or (wait_until is null and state not in ('WAITING', 'TIMED_OUT'))))
-- proposed: a step without an event may wait if it has a wake-up time; TIMED_OUT stays event-only
check ((event_name is null) = (event_key is null)
       and (event_name is not null or state <> 'TIMED_OUT')
       and (state <> 'WAITING' or event_name is not null or wait_until is not null))
```

`wait_until` stays set after the sleep completed (`SUCCEEDED`), so the history and `findJob`
show when the job slept. The comments on `bikesched_jobs` ("a WAITING job waits for an event") and
`bikesched_steps` are updated; `bikesched_steps_waiting_idx` (`event_name, event_key where state =
'WAITING'`) is unaffected: publishing matches on the event columns, which are null for sleeps.

### 4.3 Flow

1. **The handler reaches the sleep.** In one transaction, fenced by the job lock
   (`StepStore.requireJobLock`):
   - no step with that name: insert it `WAITING` with `wait_until`; if `wait_until <= now()`
     (`sleepUntil` with a past time) insert it `SUCCEEDED` instead and return;
   - `WAITING` and `wait_until <= now()`: mark it `SUCCEEDED` (`finished_at = now()`) and return;
   - `WAITING` and not yet due (claimed early, e.g. after a release): suspend again;
   - `SUCCEEDED`: return;
   - a step of that name that is a regular step or an event wait: `IllegalStateException`, as for
     event waits today ("step names must be unique within a job").
2. **Suspending.** The handler throws `JobSuspended`, the processor ends the attempt with the
   existing `SUSPEND_JOB_SQL`: job `WAITING`, `scheduled_at = wait_until`, attempt given back. No
   event advisory lock is taken (nothing can publish to a sleep).
3. **Waking up.** The poller claims the job once `scheduled_at` has passed (it already claims
   `WAITING` jobs); the handler runs again and step 1 completes the sleep.

"Due" is always decided by the database's `now()`, never the node clock.

### 4.4 Sealed `Suspension`

Today `JobSuspended.Suspension(stepName, eventName, eventKey, waitUntil)` assumes an event. It
becomes a sealed type, so every place that handles a suspension handles both kinds explicitly:

```java
sealed interface Suspension permits EventSuspension, SleepSuspension {
  String stepName();
  Instant waitUntil(); // deadline of an event wait (null: none) or wake-up time of a sleep
}
record EventSuspension(String stepName, String eventName, String eventKey, Instant waitUntil)
    implements Suspension {}
record SleepSuspension(String stepName, Instant waitUntil) implements Suspension {}
```

`JobProcessor.recordSuspension` switches over it: an `EventSuspension` takes the event's advisory
lock and logs "waiting for event …"; a `SleepSuspension` suspends without the lock and logs
"sleeping until …". `EventStore.suspendJob` is split accordingly (the SQL is shared; the sleep path
can live in `StepStore`, which owns the step).

### 4.5 Interplay with existing features

| Feature | Behaviour with a sleeping job |
|---|---|
| Attempts | sleeping does not use up an attempt (as event waits) |
| Job timeout | covers the running attempt only; the sleep ends the attempt |
| Cancellation | the `WAITING` sleep step becomes `CANCELLED` (existing cancel path), compensations run |
| Retry after a failure | a completed sleep is skipped; an unfinished one keeps its `wait_until` |
| Job history | step `WAITING` ("sleeping until …"), `SUCCEEDED`; job "suspended at step …" |
| `JobListener` | attempt outcome `WAITING`, as for event waits |
| `findJob` | state `WAITING`, `scheduled_at` = wake-up time |
| Recurring jobs | an occurrence that sleeps stays the pending occurrence; fixed-rate keeps its planned time |
| Node dies while the job sleeps | nothing to release: no lock is held while `WAITING` |

### 4.6 Where sleeping is rejected

- Inside a step handler: `StepGuard` rejects it, like nested steps and event waits.
- During compensation replay: a `SUCCEEDED` sleep returns, an unfinished one ends the replay
  (`CompensationReplayEnded`), exactly like an event wait; a compensation never starts a sleep.

### 4.7 Rejected alternatives

- **A new job state `SLEEPING`.** Clearer in `findJob`, but every query, check constraint and
  index on job states (claiming, cancel, cleanup, `JobState`) gains a case for the same behaviour
  as `WAITING` (due at `scheduled_at`, no lock, attempt given back). The step tells what the job
  waits for.
- **A separate table for timers.** A sleep is a step: it has a name, a state, a result-less
  completion and must be skipped on replay. A second table duplicates all of that.
- **Sleeping in the thread for short durations** (e.g. below the poll interval): holds a
  concurrency slot and is lost on a crash; a handler that wants that can call `Thread.sleep`.
- **Sleep as an event wait with a timeout and no event.** Works mechanically, but then the
  `TIMED_OUT` state and the timeout handler leak into a feature that has neither.

## 5. Documentation

- A section "Sleeping" in `docs/steps.md`: API, wake-up precision (poll
  interval), the stored wake-up time wins, the lifecycle (`WAITING` → `SUCCEEDED`/`CANCELLED`).
- `Job` method table in `docs/jobs.md`, Kotlin table in `docs/kotlin.md`, step states in
  `docs/database.md`.
- ADR: sleep as a step without an event, sealed suspension, no new job state.

## 6. Testing

Core (`AbstractBikeschedTest`, a new `JobSleepTest`):

1. `sleep` suspends the job (`WAITING`, `scheduled_at` ≈ now + duration, attempts given back) and
   it continues after the duration; steps before the sleep run once.
2. `sleepUntil` with a past time completes right away without suspending.
3. A changed argument on the next run does not move the wake-up time.
4. A job claimed before its wake-up time (forced by setting `scheduled_at` back) suspends again.
5. Cancelling a sleeping job marks the step `CANCELLED` and runs compensations.
6. Sleep inside a step handler and during compensation replay are rejected / end the replay.
7. Name clash with a regular step and with an event wait → `IllegalStateException`.
8. Job history entries and log lines (`LogCapture`) for sleeping and waking.
9. Schema: the new check constraint rejects `WAITING` without event and without `wait_until`, and
   `TIMED_OUT` without event (`SchemaMigratorTest` or a store test).

Kotlin: `sleep(name, kotlin.time.Duration)` end to end.

## 7. Implementation steps

1. Sealed `Suspension` (`EventSuspension`, `SleepSuspension`), processor switch; no behaviour
   change, existing tests stay green.
2. Schema: step check constraint and comments in `V001`; store method for the sleep step (insert,
   complete when due, return the state) with history records.
3. `Job.sleep`/`sleepUntil` in `DefaultJob` (guard, compensation replay, suspension), tests 1–9.
4. Kotlin extension and test.
5. Docs and ADR; remove the entry from `IDEAS.md`.

## 8. Effort

Small to medium: one store method, one `DefaultJob` method pair, the suspension refactoring, one
constraint; no new table, state or index.

## 9. Decisions (former open questions)

1. Step name required: `sleep(stepName, duration)`, `sleepUntil(stepName, time)` (option A).
2. Method names: `sleep` / `sleepUntil`.
3. Return value: `void`.
4. A changed argument on a later run is ignored silently and documented in the Javadoc.
5. Documentation: a "Sleeping" section in `docs/steps.md`.
6. Suspension: a sealed type (`EventSuspension`, `SleepSuspension`), see §4.4.
