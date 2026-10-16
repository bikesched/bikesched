# Specification: postponing jobs

Status: **implemented** ([ADR 0037](../decisions/0037-job-postpone.md); user docs:
[Postponing](../jobs/README.md#postponing)).

## 1. Goal

A handler that can't do its work right now (a rate limit, a busy provider, a closed time window)
ends the attempt and runs again later, without using up an attempt or recording a failure:

```java
bikesched.registerJobHandler("charge", job -> {
    Order order = job.payload(Order.class);
    job.step("reserve", () -> stock.reserve(order));
    String chargeId = job.step("charge", String.class, step -> {
        var probe = bucket.tryConsumeAndReturnRemaining(1);   // e.g. Bucket4j, cluster-wide
        if (!probe.isConsumed()) {
            job.postpone(Duration.ofNanos(probe.getNanosToWaitForRefill()));   // does not return
        }
        return provider.charge(order, step.jobId() + ":" + step.name());
    });
    job.step("mark-paid", () -> orders.markPaid(order, chargeId));
});
```

Rate and concurrency limits stay in libraries (Bucket4j, Resilience4j); `postpone` is the hook
they need from bikesched.

## 2. Today

| Workaround | What goes wrong |
|---|---|
| Block in the handler until a permit is free | Holds a job slot (`maxConcurrentJobs`): other types starve behind a slow provider |
| `job.fail("busy").retryAfter(d)` | Uses an attempt (the job fails permanently after enough throttling), sets `last_error`, reports `RETRYING`, records a failure in the history |
| `job.fail(…).remainingAttempts(n).retryAfter(d)` | Keeps the job alive by guessing a count; still a failure in metrics and history |
| `job.sleep("wait-" + i, d)` with generated names | A durable step per postponement: rows pile up, every replay walks all of them, and a later replay skips the check |

## 3. Scope

In scope:

- `Job.postpone(Duration)`, in the handler and inside a step's handler.
- A step postponed from inside keeps no result and runs again on the next attempt.
- Kotlin: `postpone(kotlin.time.Duration)` with the other duration helpers.
- `JobAttemptOutcome.POSTPONED` for the job listener (and the Micrometer tag).
- A rate-limit example with [Bucket4j](https://github.com/bucket4j/bucket4j) (4.7).
- Docs (`jobs.md`, `steps.md`, `kotlin.md`, `database.md`), an ADR.

Not in scope:

- Rate or concurrency limits in bikesched (a library decides; limits in the claim query are a
  separate idea).
- `postpone(Instant)`, a limit on the number of postponements, postponing from outside the
  handler.

## 4. Design

### 4.1 API

```java
// org.bikesched.job.Job
/**
 * Ends the current attempt and runs the job again once the delay has passed, without using up
 * an attempt or recording a failure. Does not return.
 */
void postpone(Duration delay);
```

- `delay` must be positive.
- It never returns: like a sleep or a wait, it ends the attempt by throwing the internal
  `JobSuspended` error. A handler catching it anyway is still postponed once it returns.
- Allowed in the handler and in a step's handler. Rejected with an `IllegalStateException` in a
  compensation, a failure handler (job or step) and a poll's check (which has `Poll.notReady()`).
- In a compensation replay (the handler runs again only to collect the compensations), it does
  nothing: the replay runs no new work.

### 4.2 Behaviour

When the handler postpones:

1. The job becomes `SCHEDULED`, due at now + delay (due now if a cancel was requested meanwhile,
   like a suspension); its attempt is given back (`attempts - 1`) and its lock released.
2. Outside a step: nothing else changes. Completed steps keep their results; code outside steps,
   including the check that postponed, runs again on the next attempt.
3. Inside a step: the step becomes `POSTPONED` without a result, its attempt given back too
   (`Step.attempts()` is the same on the next run), so the next attempt runs it again from the
   start. Steps completed before it are skipped on the next run, so the check sits with the side
   effect it guards: a later replay of a completed step never runs it again.
4. The poller wakes itself at the due time (`dueAt`, like retries); other nodes are not notified.

A postponed job keeps its priority and timeout; its payload, steps and attempt count stay as they
are. A recurring occurrence keeps its planned run (`fixed_rate_at`); the next occurrence is
scheduled when this one ends.

### 4.3 Storage

Changed in `V001` in place:

- `bikesched_steps_state_check` and `bikesched_job_history_state_check` (step entries) get
  `POSTPONED`. `START_STEP_SQL` already restarts any step that is not `SUCCEEDED` or
  `COMPENSATED`; cancelling a job leaves a `POSTPONED` step as it is (like `FAILED`).
- One statement per case in the store (`JobStore.POSTPONE_JOB_SQL`, `StepStore.POSTPONE_STEP_SQL`),
  run in one transaction fenced by the lock token; classified in `DueJobsWritePathsTest` as not
  notifying (`dueAt`).
- Job history: see 4.4.

### 4.4 Job history

History entries mirror the states, so a postponed job shows `SCHEDULED` (the job's state) with
the reason in the detail, and a postponed step shows its own `POSTPONED` state. Like a
suspension, the job's entry has the attempt given back. A job that reserves stock, is throttled
in `charge` once and then completes (attempt = the job's attempt count after the change):

| Step | State | Attempt | Detail |
|---|---|---|---|
| | `SCHEDULED` | 0 | `due at 12:00:00` |
| | `RUNNING` | 1 | |
| `reserve` | `RUNNING` | 1 | |
| `reserve` | `SUCCEEDED` | 1 | |
| `charge` | `RUNNING` | 1 | |
| `charge` | `POSTPONED` | 1 | `until 12:01:00` |
| | `SCHEDULED` | 0 | `postponed in step 'charge' until 12:01:00` |
| | `RUNNING` | 1 | |
| `charge` | `RUNNING` | 1 | none: no `step attempt 2`, its attempt was given back |
| `charge` | `SUCCEEDED` | 1 | |
| `mark-paid` | `RUNNING`, `SUCCEEDED` | 1 | |
| | `SUCCEEDED` | 1 | |

Postponed outside a step, only the job's two entries appear: `SCHEDULED` with `postponed until
12:01:00`, then `RUNNING` again. Every postponement adds these entries (two, or three in a step);
the history retention removes them like any other.

### 4.5 Internals

- `JobSuspended.Suspension` gets `PostponeSuspension(String stepName, Instant waitUntil)`;
  `stepName` is `null` outside a step.
- `DefaultJob.postpone` checks the step guard's state: no guard running or a step handler running
  is fine, anything else throws. It sets the suspension (`job.suspension()`) and throws.
- `executeStep` catches `JobSuspended` with a postponement of its own step and rethrows it
  without completing or failing the step; `JobProcessor.recordSuspension` writes both rows.

### 4.6 Kotlin

```kotlin
step<String>("charge") {
    if (!bucket.tryConsume(1)) postpone(1.minutes)
    provider.charge(order)
}
```

### 4.7 Example: rate limit with Bucket4j

A new package `org.bikesched.examples.ratelimit` in `bikesched-examples/java`, run with
`./gradlew :bikesched-examples:java:runRateLimit`:

- `SimulatedSmsProvider` accepts at most 2 messages per second and prints each call with a
  timestamp (`[sms]      sent to … (call n)`); a call over its quota is rejected and printed too,
  so the output shows it never happens.
- The job type `send-sms` sends one message in a step guarded by a Bucket4j bucket of one token
  every 600 ms, a little under the provider's limit (the time between taking a token and calling
  the provider must not push a third call into a second). Without a token the step postpones by
  the probe's wait time:

  ```java
  job.step("send", String.class, step -> {
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      if (!probe.isConsumed()) {
          job.postpone(Duration.ofNanos(probe.getNanosToWaitForRefill()));
      }
      return provider.send(sms.to(), sms.text());
  });
  ```

- The bucket lives in PostgreSQL (`bucket4j_jdk17-postgresql`,
  `Bucket4jPostgreSQL.advisoryLockBasedBuilder(dataSource)` with `PrimaryKeyMapper.STRING` and its
  own table `ratelimit_buckets (id text primary key, state bytea)`, created by the example), so
  the limit holds across nodes: `main` starts two nodes and schedules 10 messages at once.
- Dependencies `com.bucket4j:bucket4j_jdk17-core` and `bucket4j_jdk17-postgresql` (8.21.0, the
  latest on Maven Central) in the version catalog, used by the examples module only; both are
  automatic modules (`io.github.bucket4j.core`).
- `RateLimitExampleTest` (shared `util.Database`): all 10 jobs succeed with `attempts = 1`, the
  provider saw 10 calls and rejected none (more than 2 within a second), and at least one step was
  postponed (a `POSTPONED` history entry).
- A section in the examples README and a line in the root README's examples list.

## 5. Rejected alternatives

- **A flag checked after the handler, like `fail()`**: inside a step, the step handler returns a
  value after the call, which would have to be discarded; outside, the handler must remember to
  return. Ending the attempt at once matches sleeps and waits, which are the same kind of thing.
- **Checking before the step instead of allowing `postpone` inside it**: code outside steps runs
  on every replay, so the check would run (and use a permit, or postpone) again after the step
  completed.
- **Undoing the step's start instead of a `POSTPONED` state** (delete the row, or restore the
  previous state): the previous state is not known after `START_STEP_SQL`, and the history would
  show a `RUNNING` step that disappeared.
- **Marking the step `FAILED` with a "postponed" error**: reuses the restart path, but a
  postponement would show up in every query and metric on failed steps.
- **A `WAITING` job, like a sleep**: reads as "waits for a step", which a postponement outside a
  step is not.
- **Counting postponements as attempts with a separate budget**: the handler (or its library)
  decides when to give up, e.g. `job.fail(…)` after a deadline it reads from the payload.

## 6. Testing

- Postponed outside a step: the job is `SCHEDULED` at about now + delay with its attempt given
  back, runs again, and succeeds; `attempts()` the same on both runs; no `last_error`.
- Postponed inside a step: the step is `POSTPONED` without a result, earlier steps are skipped on
  the next run, the step runs again with the same `Step.attempts()`, the job succeeds.
- Postponed repeatedly: never fails, whatever `maxAttempts`.
- A cancel requested while the attempt runs: the job is cancelled right after, not postponed.
- Rejected in a compensation, a job failure handler, a step failure handler and a poll check;
  ignored in a compensation replay; non-positive delays rejected.
- The handler catching the error still postpones.
- History entries, `JobAttemptOutcome.POSTPONED` (also the Micrometer tag), the `dueAt` wake-up.
- Kotlin: `postpone(1.seconds)` in a handler and a step.
- The rate-limit example's test (4.7).

## 7. Implementation steps

1. Schema constraints, store statements, `DueJobsWritePathsTest`.
2. `Job.postpone`, `PostponeSuspension`, `DefaultJob`/`executeStep`, `JobProcessor`, outcome enum.
3. Kotlin helper, Micrometer test.
4. Rate-limit example with Bucket4j (catalog entries, package, `runRateLimit` task, test, README).
5. Docs (`jobs.md` new section "Postponing", `steps.md` cross-link from idempotency keys,
   `kotlin.md`, `database.md`), ADR.

## 8. Open questions

None.

## 9. Decisions

1. A step postponed from inside gets the new state `POSTPONED`, not `FAILED` with a "postponed"
   error: a postponement is no failure and must not show up in queries on `FAILED` (4.3).
2. A postponed job is `SCHEDULED`, like a retry, not `WAITING`: it waits for no step (4.2).
3. The job history shows the job as `SCHEDULED` with `postponed until …` (`in step '…'`) and the
   step as `POSTPONED`; no `POSTPONED` job state in the history, which mirrors the job's states
   (4.4).
4. The rate-limit example's bucket allows one message every 600 ms instead of two per second:
   with a bucket equal to the provider's limit, the time between taking a token and calling the
   provider can push a third call into a second (4.7).
5. A step handler that catches the postponement and returns is still postponed: its result is not
   stored (`DefaultJob` throws the suspension again after the handler).
