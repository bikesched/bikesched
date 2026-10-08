# Specification: poll steps (`job.newPoll`)

Status: **draft, not implemented**.

## 1. Goal

A job handler can poll an external service until it returns a result, durably and without
holding a thread or a concurrency slot between checks, using up attempts or adding a row per
check:

```java
Report report = job.newPoll("await-report", Report.class, Duration.ofSeconds(30))
    .timeout(Duration.ofHours(2))
    .run(step -> switch (reports.status(reportId)) {
      case Done d -> Poll.ready(d.report());
      case Pending _ -> Poll.notReady();
    });
```

Each check runs the poll handler once. "Not ready" suspends the job until the next check, like a
sleep; "ready" completes the step with the value, which later runs return like any step result.

## 2. Today

Users can build polling from steps, sleeps and failures, each with a drawback:

| Pattern | What goes wrong |
|---|---|
| Check, then `job.fail("not ready").remainingAttempts(n).retryAfter(d)` | every check is a failed attempt: `last_error`, failure metrics and listener events, attempts double as the poll budget, running out ends `FAILED` and starts compensation |
| Loop of `job.step("check-" + i, …)` and `job.sleep("wait-" + i, …)` | two step rows (plus history) per check; every wake-up replays all earlier checks, one `StepStore.findStep` query each, so n checks cost O(n²) queries |
| Loop with `Thread.sleep` inside one step | holds a concurrency slot and a running attempt for the whole time; the job timeout must cover the loop; a crash starts the loop again |
| Return "pending" from a step | stored for good: the step never runs again |

No pattern has a deadline with a fallback, as event waits have.

## 3. Non-goals

- Combining a poll with an event wait in one step ("wake on the webhook or the next check"). The
  two compose one after the other (event wait with timeout, then a poll, see §8); a combined wait is
  a possible follow-up.
- Compensation for polls: a poll handler only reads. Side effects go into a step after the poll.
- Precise intervals: a due check runs on the next poll of the node, so up to one `pollInterval` late
  (5 seconds by default), like sleeps. Intervals of a few seconds belong into a loop inside a step.
- Polls inside step handlers and compensations: rejected like sleeps and event waits (§4.7).

## 4. Design

### 4.1 API

In `org.bikesched.job`, on `Job` next to `newStep`:

```java
<T> PollBuilder<T> newPoll(String name, Class<T> resultType, Duration interval);
<T> PollBuilder<T> newPoll(String name, Class<T> resultType, RetryBackoff interval);
```

- The result type is needed for the same reason as for `newStep`: later runs read the stored value
  back through the `JsonMapper`.
- The interval is a required argument: there is no default that fits every service. A `Duration`
  must be positive.
- A `RetryBackoff` (e.g. `RetryBackoff.exponential(5s, 5min)`, with its jitter) grows the
  interval: it is called after each check that is not ready, with that check's number (1, 2, …,
  the step's `attempts`, which also counts checks that threw), and the next check runs at
  `now() + delay`. It is not called for a ready check, nor for a throwing one (that is a failed job
  attempt, delayed by the node's own `retryBackoff`). A backoff returning zero (`none()`) checks on
  every node poll; one that throws or returns `null` or a negative delay is logged (WARN) and falls
  back to zero, like the node's retry backoff. `RetryBackoff`'s Javadoc gains a sentence on this
  second use.

```java
public interface PollBuilder<T> {
  /** Stop polling after {@code timeout}: run throws a PollTimeoutException (default: none). */
  PollBuilder<T> timeout(Duration timeout);

  /** Stop polling after {@code timeout}: run returns the handler's fallback, not stored. */
  PollBuilder<T> timeout(Duration timeout, TimeoutHandler<? extends T> handler);

  /** Runs the checks; returns the ready value, or the timeout handler's fallback. */
  T run(PollHandler<T> handler) throws Exception;
}

@FunctionalInterface
public interface PollHandler<T> {
  Poll<T> handle(Step step) throws Exception;   // step.attempts(): the number of this check
}

public sealed interface Poll<T> {
  record Ready<T>(T value) implements Poll<T> {}
  record NotReady<T>(Duration retryAfter) implements Poll<T> {}   // null: the poll's interval

  static <T> Poll<T> ready(T value) { … }
  static <T> Poll<T> notReady() { … }
  static <T> Poll<T> notReady(Duration retryAfter) { … }   // e.g. from a Retry-After header
}
```

- The poll handler receives the `Step` like a step handler (job id, name, number of the check), e.g.
  to derive an idempotency key or log the check.
- `TimeoutHandler` (package `org.bikesched.event`) is reused as is; `Job` already refers to that
  package.
- `PollTimeoutException extends BikeschedException`, like `EventTimeoutException`. It ends the job
  as an event wait's default timeout does: `fail(message).remainingAttempts(0)`, because a retry
  would time out again right away.
- `Poll.notReady(retryAfter)` overrides the interval for the next check only.
- Kotlin (`bikesched-kotlin`), one style per operation:
  `poll<Report>("await-report", interval = 30.seconds, timeout = 2.hours) { step -> … }`, with
  `kotlin.time.Duration`, or `interval: RetryBackoff` (an overload); the fallback variant takes
  `onTimeout: (() -> T)?`.

### 4.2 Outcomes of a check

| The poll handler… | Step | Job |
|---|---|---|
| returns `Poll.ready(v)` | `SUCCEEDED`, `result = v` | continues |
| returns `Poll.notReady()` before the deadline | `WAITING`, `wait_until` = next check | suspended (`WAITING`), attempt given back |
| returns `Poll.notReady()` at or after the deadline | `TIMED_OUT` | `PollTimeoutException`, or the fallback |
| throws | `FAILED`, `last_error` | the attempt fails; retried with the node's backoff while attempts remain |

After an exception the next attempt checks again right away (the step is not `WAITING`); the
deadline keeps counting. Exceptions are for errors a retry may fix, as for steps; a provider
answering "failed" is a value (`Poll.ready(new Failed(…))`, see
[Business outcomes as results](../steps.md#business-outcomes-as-results)).

### 4.3 Storage

A poll is a step of the new kind `POLL`: one `bikesched_steps` row for all checks.

- `attempts`: the number of checks so far (incremented when a check starts, like for steps).
- `wait_until`: the time of the next check while `WAITING`, at most the deadline, so the last check
  runs at the deadline. `SUSPEND_JOB_SQL` already makes the job due at `wait_until`.
- New column `timeout_at timestamptz`: the deadline, `now() + timeout` computed by the database on
  the first check. As with sleeps, the stored value wins on later runs, so a changed timeout in a
  deploy doesn't move a poll that has already started.
- `result`: the ready value; `last_error`: the exception of a failed check.

Schema diff in `V001` (in place, unreleased):

```sql
    timeout_at  timestamptz,            -- the deadline of a poll, none if null

    constraint bikesched_steps_kind_check
        check (kind in ('STEP', 'SLEEP', 'EVENT_WAIT', 'EVENT_PUBLISH', 'CHILD_JOB', 'POLL')),
    -- in bikesched_steps_event_check: polls time out too
               and (kind in ('EVENT_WAIT', 'CHILD_JOB', 'POLL') or state <> 'TIMED_OUT')),
    -- Only sleeps, event waits, child jobs and polls wait; sleeps and waiting polls need their
    -- wake-up time; only polls have a deadline of their own.
    constraint bikesched_steps_wait_check
        check ((state <> 'WAITING' or kind in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB', 'POLL'))
               and (wait_until is null or kind in ('SLEEP', 'EVENT_WAIT', 'CHILD_JOB', 'POLL'))
               and (kind <> 'SLEEP' or wait_until is not null)
               and (kind <> 'POLL' or state <> 'WAITING' or wait_until is not null)
               and (timeout_at is null or kind = 'POLL')),
```

No new index (no new access path), no new job state.

### 4.4 Flow

1. **The handler reaches the poll.** One transaction, fenced by the job lock
   (`StepStore.requireJobLock`), `select … for update` on the step:
   - `SUCCEEDED`: return the stored result (no check).
   - `TIMED_OUT`: return "timed out" (§4.5).
   - `WAITING` and `wait_until > now()` (claimed early, e.g. after a release or a cancel request):
     suspend again without a check.
   - another kind with that name: `IllegalStateException` ("step names must be unique within a
     job"), as for sleeps.
   - otherwise (no row, due `WAITING`, `FAILED`, or `RUNNING` left by a crash): upsert `RUNNING`
     with `locked_by`, `attempts + 1`, `timeout_at = coalesce(timeout_at, now() + ?)`; returns the
     check number.
2. **The check** runs outside a transaction (it calls an external service), inside `StepGuard`, so
   it can't run steps, sleeps or waits.
3. **Recording**, again fenced:
   - ready: the existing `completeStep` (`compensable = false`).
   - not ready: `timeout_at <= now()` → `TIMED_OUT`; otherwise `WAITING` with
     `wait_until = least(now() + delay, timeout_at)`, `locked_by = null`, `last_error = null`. Then
     the handler throws `JobSuspended` with a new `PollSuspension(stepName, waitUntil)`, and the
     processor ends the attempt with `SUSPEND_JOB_SQL` (no advisory lock).
   - exception: the existing `failStep`, then rethrow.

The delay comes from `retryAfter` if given, else from the interval (`Duration`, or
`RetryBackoff.delay(check)`), capped at the deadline. All
times use the database's `now()`.

**No lost wake-up:** nothing else writes a poll step, so suspending in a later transaction than
the `WAITING` write races with nothing (unlike event waits and child jobs).

### 4.5 Timeouts

- Without `timeout(…)`, the poll checks until it is ready or the job is cancelled. There is no
  node-wide default.
- After the deadline the step stays `TIMED_OUT`; every later run of the handler gets the same
  outcome again: `PollTimeoutException` (`Polling in step 'await-report' timed out after PT2H
  (3 checks)`), or the timeout handler's fallback, which is not stored and runs on every replay,
  as for event waits.
- Polling again after a timeout needs a new step name.

### 4.6 History, logs, listener

Decided: a poll records its first wait and its final outcome, not every check.

- **The step** records only its final outcome: `SUCCEEDED` (`ready after 3 checks`), `TIMED_OUT`
  (`timed out after 3 checks`), `FAILED` (`check 3 failed: …`) or `CANCELLED` (existing cancel
  path). Starting a check and waiting for the next one record nothing for the step.
- **The job** records the suspension after the first check (`WAITING`, `suspended at step
  'await-report', polling`), then nothing for the later checks: no claim entry while its poll step
  is `WAITING`, no suspension entry from check 2 on. A poll with 3 checks reads:

  ```
  RUNNING    claimed, attempt 1
  WAITING    suspended at step 'await-report', polling
  step await-report SUCCEEDED (ready after 3 checks)
  SUCCEEDED
  ```

  The job's return from `WAITING` has no entry of its own; the poll step's outcome stands for it.
  Failed checks, cancellations and releases of dead nodes record their entries as usual.
- Skipping only the suspension entries was rejected: the history would show the job `RUNNING` while
  it waited without a lock (unlike `findJob`), and the claim entries (`attempt 1` every time,
  because suspending gives the attempt back) would look like attempts that never ended.
- **Implementation:** the suspension knows the check number from `PollSuspension` and skips the
  entry from check 2 on. The claim's history path (only with the history enabled) finds claimed
  jobs with a waiting poll in one query per claim batch, on the primary key index, and leaves them
  out:

  ```sql
  select job_id from bikesched_steps
   where job_id = any(?) and kind = 'POLL' and state = 'WAITING'
  ```

- Logs (DEBUG, `DefaultJob`): `Step {name} of job {id}: check {n} not ready, checking again at {t}`,
  `Step {name} of job {id} ready after {n} checks`, `… timed out after {n} checks`. The processor's
  outcome line stays per check.
- `JobListener`: each check that is not ready ends its attempt with outcome `WAITING`, like sleeps.

### 4.7 Interplay with existing features

| Feature | Behaviour with a poll |
|---|---|
| Attempts | checks that are not ready don't use up attempts; a throwing check does |
| Job timeout | covers one attempt, so one check (plus the steps before it in that run) |
| Cancellation | a `WAITING` poll step becomes `CANCELLED` (existing cancel path), compensations of earlier steps run |
| Compensation replay | a `SUCCEEDED` poll returns its result; any other state ends the replay (`CompensationReplayEnded`), like sleeps; a replay never starts a check |
| `bikesched.retry(id)` | a `FAILED` poll step checks again; a `TIMED_OUT` one stays timed out |
| Step handlers, compensations | `newPoll(…).run` inside them throws `IllegalStateException` (`StepGuard`) |
| Timeout handlers | may run polls, as they may run steps (a poll as the fallback of an event wait) |
| Recurring jobs | an occurrence that polls stays the pending occurrence |
| Node dies during a check | the step is `RUNNING` without its node; the released job checks again on the next run |
| `findJob` | `WAITING`, `scheduled_at` = next check |

### 4.8 Rejected alternatives

- **An option on `newStep` (`.pollEvery(d)`)**: the step's handler would have to return something
  that can mean "not ready", unlike every other step; a separate builder keeps `StepHandler`
  unchanged.
- **`Optional<T>` as the handler's result**: can't carry a per-check `retryAfter`, and can't make
  `null` a ready value.
- **Exceptions as "not ready"**: would hide real errors and mix them with expected waiting.
- **A row per check** (pattern B in §2, generated names): quadratic replays and growing tables.
- **A default interval**: no value fits every service (rate limits, latency of the result).
- **A builder method `backoff(RetryBackoff)` next to `interval(Duration)`**: the two are mutually
  exclusive and would need a "last call wins" rule or a builder type of their own; overloads of
  the required argument enforce it at compile time.
- **`Duration` only, growth by hand** (`notReady(...)` computed from `step.attempts()`): less API,
  but every user writes the formula, without jitter.
- **The deadline in `wait_until`, the next check elsewhere**: `SUSPEND_JOB_SQL` and the claim make
  jobs due at `wait_until`; moving the next check out of it would change that shared path for all
  waiting steps.
- **The deadline computed from `started_at` + timeout**: `started_at` restarts with every check,
  and a changed timeout would move running polls.
- **A history entry per check, or skipping only the suspension entries**: see §4.6.

## 5. Documentation

- `docs/steps.md`: a section "Polling" after "Sleeping": API, outcomes table, interval and
  backoff, `retryAfter`, timeouts, precision, the patterns of §2 as what not to do; a short
  subsection "Webhooks with a polling fallback" pointing to the example.
- `docs/jobs.md` (`Job` method table, the history section: polls record their first wait and their
  outcome), `docs/kotlin.md` (extension table), `docs/database.md` (step kind `POLL`, column
  `timeout_at`).
- ADR: poll as one step of its own kind, required interval with a `RetryBackoff` overload, deadline
  in `timeout_at`, history of the first wait and the outcome.
- `IDEAS.md`: remove the "Poll step" entry; in "Fallbacks in event timeout handlers", the "try again
  in 5 minutes" part is then covered by a poll (update or remove the entry).

## 6. Testing

Core, a new `PollTest` (`AbstractBikeschedTest`, short poll interval):

1. Ready on the third check: job `SUCCEEDED`, one step row (`POLL`, `SUCCEEDED`, `attempts = 3`,
   result), job `attempts = 1`, steps before the poll ran once.
2. A later run (a step after the poll fails once) returns the stored value without a check.
3. Not ready: job `WAITING`, `scheduled_at` = step `wait_until` ≈ now + interval, attempt given
   back.
4. A `RetryBackoff` receives check numbers 1, 2, … (also counting a throwing check) and is not
   called for the ready check; `notReady(retryAfter)` wins for one check; a throwing backoff logs a
   WARN and checks on the next poll; a non-positive `Duration` is rejected.
5. Timeout: last check at the deadline (`wait_until` capped), then `TIMED_OUT` and the job `FAILED`
   with `PollTimeoutException` (no retry); with a fallback, the value is returned on every replay
   and not stored.
6. A changed interval or timeout on a later run doesn't move `wait_until` / `timeout_at`.
7. A throwing check: step `FAILED` with `last_error`, attempt used up, the next attempt checks right
   away; after the last attempt the job is `FAILED`.
8. Claimed early (`scheduled_at` set back): suspends again without a check.
9. Cancelling a polling job: step `CANCELLED`, compensations of earlier steps run.
10. Compensation replay ends at an unfinished poll; a `SUCCEEDED` poll returns its result.
11. Rejected in step handlers and compensations; allowed in an event wait's timeout handler.
12. Name clash with a step of another kind → `IllegalStateException`.
13. History with 3 checks: exactly the entries of §4.6 (one claim, one suspension, the step's
    outcome, the job's end); a failing check adds its step and job entries; logs via `LogCapture`.
14. Schema (`SchemaMigratorTest` or a store test): `WAITING` poll without `wait_until`,
    `timeout_at` on another kind, `TIMED_OUT` sleep rejected by name.

Store level (`AbstractStoreTest`): the start/record statements, fencing (writes after the lock
moved throw `JobLockLostException`), `coalesce` of `timeout_at`, the claim leaving out jobs with a
waiting poll from the history.

Kotlin: `poll<T>(…)` end to end with a data class result, with a `Duration` and a `RetryBackoff`.

## 7. Implementation steps

1. Schema: `timeout_at`, kind `POLL`, the constraint changes; `StepStore` methods to start and
   record a check, with history records (§4.6), the claim's history filter; store tests.
2. API (`newPoll` overloads, `PollBuilder`, `PollHandler`, `Poll`, `PollTimeoutException`),
   `DefaultPollBuilder` and the poll path in `DefaultJob` (guard, replay, suspension),
   `PollSuspension` in the sealed `Suspension` and the processor's switch; tests 1–13.
3. Kotlin extension and test.
4. Example `examples.poll` (§8).
5. Docs, ADR, `IDEAS.md`.

## 8. Example: webhook with polling fallback

Package `org.bikesched.examples.poll`, task `runPoll`, `PollExampleTest`, a section in the examples
README and a mention in the root README.

Scenario: a payment provider settles payments asynchronously and sends a webhook, which can get
lost. The job waits for the webhook for a short time and polls the provider if it doesn't arrive:

```java
String paymentId = job.step("create-payment", String.class,
    step -> provider.createPayment(payment, step.jobId() + ":" + step.name()));

PaymentSettled webhook = job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
    .timeout(Duration.ofSeconds(3), () -> null)
    .receive();
PaymentSettled settled = webhook != null
    ? webhook
    : job.newPoll("poll-payment", PaymentSettled.class, Duration.ofSeconds(1))   // webhook lost
        .timeout(Duration.ofSeconds(30))
        .run(_ -> provider.status(paymentId) instanceof PaymentSettled s
            ? Poll.ready(s)
            : Poll.notReady());
job.step("mark-paid", Void.class, _ -> { orders.markPaid(settled); return null; });
```

- `SimulatedPaymentProvider` prints its calls (`[provider] …`, optional `PrintStream` for the
  test), counts status calls per payment, settles a payment after a fixed number of status checks
  and publishes the webhook (`bikesched.newEvent(PAYMENT_SETTLED, paymentId)`) unless the payment
  asks for it to be lost.
- The variant is a visible value in the payload: `main` runs
  `example.pay(new Payment(…, Webhook.DELIVERED))` and `example.pay(new Payment(…, Webhook.LOST))`.
- `PollExampleTest`: delivered → `SUCCEEDED`, no status call, no poll step; lost → `SUCCEEDED`, the
  number of status calls equals the poll step's `attempts`, one history entry for the poll step.
- The poll could also run inside the timeout handler; the example uses the explicit form, which
  reads more easily. Test 11 covers the other form.

## 9. Decisions

1. A dedicated poll step: `job.newPoll(name, resultType, interval)` (the result type is needed to
   read the stored value back on replays).
2. Result type `Poll<T>` (`ready(value)`, `notReady()`, `notReady(retryAfter)`).
3. An exception in a poll handler fails the attempt.
4. History: the step records only its final outcome; the job records the first suspension and
   skips the claim and suspension entries of later checks (§4.6).
5. Poll first; webhook + polling as an example (§8), a combined wait later if needed.
6. The interval is a required argument, no default.
7. Growing intervals: an overload taking a `RetryBackoff`, called with the number of each check
   that is not ready (§4.1).
8. A timeout without a fallback fails the job permanently with `PollTimeoutException`, like event
   waits.

No open questions left; the spec is ready to implement.
