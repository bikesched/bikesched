# Specification: failure handlers for event waits and polls

Status: **implemented** (core, Kotlin, examples; ADR 0034). User docs: `docs/events.md#timeouts`,
`docs/steps.md#polling`, `docs/kotlin.md`.

## 1. Goal

Event waits and polls handle a timeout with the step failure handler instead of a
`TimeoutHandler`: a timeout is a failure of the wait, and `onFailure` decides with
`failing.resolve(fallback)` or lets the job fail. One concept for steps, waits and polls, and the
fallback is stored like any step result. Event waits take their payload class when created, like
steps and polls:

```java
Payment payment = job.waitForEvent("await-payment", "payment-received", order.id(), Payment.class)
    .timeout(Duration.ofDays(3))
    .onFailure(failing -> failing.resolve(Payment.MISSING))
    .receive();

Status status = job.newPoll("poll-status", Status.class, Duration.ofMinutes(1))
    .timeout(Duration.ofHours(1))
    .onFailure(failing -> failing.resolve(Status.UNKNOWN))
    .run(_ -> provider.status(id).map(Poll::ready).orElseGet(Poll::notReady));
```

## 2. Today

- Two wait types: `waitForEvent(step, name, key)` returns an untyped `EventWait` that learns its
  type in `receive(Class)`; `waitForEvent(step, EventType, key)` returns a `TypedEventWait<T>`.
- `EventWait.timeout(Duration, TimeoutHandler<T>)` returns `EventWait.Timed<T>`;
  `TypedEventWait.timeout(Duration, TimeoutHandler)` and `PollBuilder.timeout(Duration,
  TimeoutHandler)` take the same handler, `T handle()` without context.
- The step is stored `TIMED_OUT`; the handler's result is not stored, it runs on every replay that
  reaches the wait, also during compensation. So it may run steps and polls (the docs' webhook
  fallback), but needs no side effects of its own.
- A timeout without a handler (an explicit poll `timeout(Duration)`, the node's
  `defaultEventTimeout`) fails the job without further attempts (`EventTimeoutException`,
  `PollTimeoutException`). A string-based `EventWait` cannot set a timeout without a handler.
- Step failure handlers (ADR 0033) get a `FailingStep<T>` with `error()` and `resolve(T)`, run
  counted (`failureHandlerAttempts`) with the step `FAILING`, and store a resolved result.

## 3. Scope

In scope:

- `onFailure(StepFailureHandler<T>)` on event waits and `PollBuilder<T>`, handling the wait's
  timeout (explicit or the node's `defaultEventTimeout`) and a poll check failing in the last
  attempt.
- One typed wait `EventWait<T>`: the string-based `waitForEvent` takes the payload class.
- `timeout(Duration)` without a handler on event waits.
- Removing `TimeoutHandler`, `TypedEventWait`, `EventWait.Timed` and the `timeout(Duration,
  TimeoutHandler)` overloads (breaking, unreleased).
- Kotlin, examples and docs following the change.

Not in scope:

- Child job waits: `waitForJob` returns `JobResult.TimedOut` as a value, the parent decides
  (`switch`, `orElse`, `orElseThrow`); no handler needed.
- Sleeps: they cannot fail.
- Payloads that can't be mapped by the `JsonMapper` when received: a bug, not a timeout.
  (A poll check that throws is a failure: see 4.1.)

## 4. Design

### 4.1 API

```java
// org.bikesched.job.Job
<T> EventWait<T> waitForEvent(String stepName, String eventName, String eventKey, Class<T> type);
<T> EventWait<T> waitForEvent(String stepName, EventType<T> eventType, String eventKey);
<T> T awaitEvent(String stepName, EventType<T> eventType, String eventKey);   // unchanged

// org.bikesched.event: replaces EventWait, EventWait.Timed and TypedEventWait
public interface EventWait<T> {
  EventWait<T> timeout(Duration timeout);            // without onFailure: the job fails
  EventWait<T> onFailure(StepFailureHandler<T> handler);
  T receive();                                       // no longer throws Exception
}

// org.bikesched.job
public interface PollBuilder<T> {
  PollBuilder<T> timeout(Duration timeout);          // unchanged
  PollBuilder<T> onFailure(StepFailureHandler<T> handler);
  T run(PollHandler<T> handler) throws Exception;
}
```

- `onFailure` also applies without `timeout(…)`: then it handles the `defaultEventTimeout`.
- A poll's `onFailure` also handles a check that throws in the job's last attempt, like a step's
  (same entry: `failStepWithHandler`); on a poll without a timeout, that is the only case.
- The class comes first, as in `newStep(name, Class)` and `newPoll(name, Class, interval)`: every
  step-like builder knows its type from the start, and one wait type remains. Every caller passes
  the class to `receive(Class)` today, so it only moves.
- `org.bikesched.event` now depends on `StepFailureHandler` in `org.bikesched.job`; `job` already
  depends on `event`. No package rule forbids it.

### 4.2 Behaviour

When the job reaches a wait or poll whose deadline has passed and it has a failure handler:

1. The step goes `TIMED_OUT` → `FAILING` with `last_error` = the `toString()` of the
   `EventTimeoutException`/`PollTimeoutException` it would have failed with (same message as
   today).
2. The handler runs right away, in this attempt, whatever attempt it is (a retry would time out
   again). From here it is `handleFailingStep` from ADR 0033: counted runs, `resolve` → step
   `SUCCEEDED` with the fallback stored, not resolved → step `FAILED` and the job fails without
   further attempts, handler throws → the step stays `FAILING` and the job gets another attempt,
   given up after `failureHandlerAttempts`.
3. Later runs and compensation replays return the stored fallback; a `FAILING`/`FAILED` wait ends
   a compensation replay like a failed step.

Without a failure handler, nothing changes: the step stays `TIMED_OUT` and the job fails without
further attempts.

Like every failure handler, it runs inside the step guard: it cannot run steps, waits or polls.
The webhook-with-polling-fallback pattern resolves with `null` and polls after `receive()` (the
docs' main sample already does).

### 4.3 Storage

No new column or state: `FAILING`, `failure_attempts` and `last_error` exist on
`bikesched_steps`.

- `bikesched_steps_event_check` ties `event_id is not null` to `state = 'SUCCEEDED'`; a resolved
  wait is `SUCCEEDED` without an event. It becomes `(event_id is not null) = (state =
  'SUCCEEDED' and last_error is null)`: resolved steps keep their `last_error`, and an event wait
  has none otherwise. No explicit marker column.
- `StepStore`: `MARK_TIMED_OUT_STEP_FAILING_SQL` marks a `TIMED_OUT` wait or poll `FAILING`;
  `FIND_FAILING_ERROR_SQL` takes the step kind. Event wait and
  poll statements return `FAILING`/`FAILED` as their state, so the job routes to the handler.
- The job history records `FAILING` and then `SUCCEEDED`/`FAILED` for the step, as for steps.
- Changed in `V001` in place.

### 4.4 Kotlin

`onTimeout: () -> T` becomes `onFailure: (FailingStep<T>) -> Unit`, like `step<T>(…, onFailure)`:

```kotlin
val payment = waitForEvent<Payment?>("await-payment", "payment-received", order.id, 3.days,
    onFailure = { it.resolve(null) })
val status = poll<Status>("poll-status", 1.minutes, timeout = 1.hours,
    onFailure = { it.resolve(Status.UNKNOWN) }) { … }
```

The `EventWait` extensions (`receive<T>()`, `receive(timeout, onTimeout)`,
`receiveOrNull(timeout)`) go: `EventWait<T>.receive()` is typed, and `waitForEvent<T>(…)` covers
the rest (`waitForEvent<Payment?>(…, timeout = 3.days, onFailure = { it.resolve(null) })`).

## 5. Rejected alternatives

- **A durable timeout handler of its own** (`onTimeout(timedOut -> timedOut.resolve(x))`): reads
  better for a timeout that's a business branch, but duplicates the counting, storing and
  resolving of the failure handler under a second name.
- **Keeping `TimeoutHandler`, storing its result**: keeps the extra concept and the ambiguity of a
  throwing handler (fail the attempt, then time out again).
- **Requiring `resolve`**: not resolving is a valid choice (alert, then fail), as for steps.
- **Typing an untyped wait later**: `EventWait.payloadType(Class)` returning a typed wait keeps two
  wait types and two ways to end a wait (`receive(Class)` or `payloadType(…).receive()`);
  `receive(Class<T>, StepFailureHandler<T>)` makes `onFailure` an argument of the verb here and a
  builder option everywhere else.

## 6. Testing

- Event wait (string name with class, `EventType`) and poll: resolve on timeout, stored and returned
  on the next run without running the handler again; resolve with `null`.
- A poll check throwing in the last attempt runs the poll's handler; resolved, the job continues.
- Not resolved: step `FAILED`, job `FAILED` after the attempt reaching the timeout, `last_error`
  from the timeout exception, failure handler given the same `error()`.
- Handler throws: another attempt runs it again; given up after `failureHandlerAttempts`.
- `onFailure` with the `defaultEventTimeout`; `timeout(Duration)` without `onFailure` fails the
  job.
- A wait resolved before compensation: the replay returns the fallback.
- The handler calling `job.step(…)` is rejected (step guard).
- Job history entries; `bikesched_steps_event_check` accepts a resolved wait.
- Kotlin: `waitForEvent`/`poll` with `onFailure`, `receiveOrNull`.

## 7. Implementation steps

1. Schema constraint, `StepStore`/`EventStore` statements for `TIMED_OUT` → `FAILING`.
2. `DefaultJob`: route timed-out waits and polls with a handler into `handleFailingStep`; remove
   the replay-only timeout branches.
3. API: `EventWait<T>` (merging `DefaultEventWait` and `DefaultTypedEventWait`), the class on
   `waitForEvent`, `onFailure` on `EventWait`/`PollBuilder`; remove `TimeoutHandler`,
   `TypedEventWait`, `EventWait.Timed`; update `Job` Javadoc samples.
4. Kotlin `Events.kt`/`Polls.kt`, examples (taxi, poll), docs (`events.md#timeouts`, `steps.md`
   polling and webhook fallback, `kotlin.md`, `configuration.md` for `defaultEventTimeout`), ADR.

## 8. Open questions

None.

## 9. Decisions

1. A poll's failure handler also handles a check throwing in the job's last attempt (4.1).
2. A resolved event wait is told apart by `last_error` in the constraint, no marker column (4.3).
3. The string-based `waitForEvent` takes the payload class; `EventWait<T>` replaces `EventWait`,
   `EventWait.Timed` and `TypedEventWait` (4.1).

## 10. Decided while implementing

1. A separate statement (`MARK_TIMED_OUT_STEP_FAILING_SQL`) marks a timed-out step `FAILING`
   instead of widening `MARK_STEP_FAILING_SQL`, which needs a `RUNNING` step locked by the node.
   The history keeps the `TIMED_OUT` entry before `FAILING`.
2. A wait or poll that stays `FAILING` (its handler threw) is handled again on the next attempt;
   unless resolved, the job fails without further attempts, also when the failure was a poll
   check (that only happens in the last attempt anyway). Otherwise a job whose timeout came in an
   early attempt would be retried into the same timeout.
3. The job's error for a poll still `FAILING` on a later attempt is `Poll '<name>' failed:
   <stored error>`; an event wait rebuilds its `EventTimeoutException` message.
4. Compensation replays end at a wait or poll that timed out, is `FAILING` or `FAILED` (before,
   a `TIMED_OUT` one ran its timeout handler again); a resolved one returns its fallback.
5. `StepStore.findFailingError` takes the step kind, so a step and a wait of the same name can't
   pick up each other's error.
6. Kotlin: the last parameter of `waitForEvent<T>` is now `onFailure`, so a trailing lambda binds
   to it (`waitForEvent<Report?>(…, timeout = 1.hours) { it.resolve(null) }`); docs and tests use
   the named argument.
