# Specification: failure handlers for jobs and steps

Status: **implemented** (core, step rework in `bikesched-kotlin`; ADR 0033). User docs:
`docs/jobs.md#failure-handlers`, `docs/steps.md#failure-handler-per-step`.

## 1. Goal

A job type can have a failure handler that runs, durably and at least once, before a job of that
type ends `FAILED` or `COMPENSATION_FAILED`, e.g. to move it to a dead-letter table or alert
someone. The job's outcome is final at that point:

```java
bikesched.registerJobHandler("charge", job -> payments.charge(job.payload(Charge.class)));
bikesched.registerJobFailureHandler("charge", failed ->
    deadLetters.insert(failed.id(), failed.type(), failed.payload(), failed.error()));
```

Step failure handlers are reworked to match: they get the stored error instead of an exception,
and can resolve the step with a fallback value so the job continues:

```java
Rate rate = job.newStep("fetch-rate", Rate.class)
    .onFailure(failing -> failing.resolve(Rate.DEFAULT))
    .run(_ -> rates.fetch(currency));
```

Both handlers receive the failure as text, never as an exception: they may run in a later attempt
or on another node, and timeouts, abandoned attempts and dead nodes have no exception.

## 2. Today

- `StepFailureHandler.handle(Step, Exception)` runs when a step throws in the job's last attempt,
  with the real exception, or on a rerun after a crash with a `BikeschedException` built from the
  stored text. A handler that keeps failing gives the job extra attempts without limit.
- Jobs have no failure handler. `JobListener.onAttemptEnded` reports the failure after the commit,
  by the recording node only (lost if it dies); the job handler does not run after a timeout, an
  abandoned attempt or a crash.

## 3. Scope

In scope:

- Core: `registerJobFailureHandler`, `failureHandler(…)` on the job builders, the `FAILING` job
  state, the reworked `StepFailureHandler`, the builder option `failureHandlerAttempts`.
- Kotlin, examples and docs for the step rework (its API breaks).
- `JobResult.orElse(T fallback)` for parents falling back on a failed child job.

Not in scope:

- Kotlin, annotations and Spring starter wiring for job failure handlers (follow-up).
- Job failure handlers changing the outcome (retry, resolve): once failing, the job's outcome is
  final.
- Failure handlers for sleeps, event waits, polls and child job steps.
- Cancelled jobs.

## 4. Design

### 4.1 Settings

`BikeschedBuilder.failureHandlerAttempts(int)`, default 3, at least 1: how often a job or step
failure handler runs before it is given up. Like other node settings, it applies on the node
running the handler.

### 4.2 Job failure handlers

```java
// org.bikesched.Bikesched
Bikesched registerJobFailureHandler(String type, JobFailureHandler handler);
<T> Bikesched registerJobFailureHandler(JobType<T> type, TypedJobFailureHandler<? super T> handler);
<T> Bikesched registerJobFailureHandler(JobTypeFailureHandler<T> handler);  // default method

// org.bikesched.job
@FunctionalInterface
public interface JobFailureHandler {
  void handle(FailedJob job) throws Exception;
}

@FunctionalInterface
public interface TypedJobFailureHandler<T> {
  void handle(FailedJob job, T payload) throws Exception;  // payload mapped to the JobType's class
}

// class-based, like JobTypeHandler; registered via registerJobFailureHandler(handler)
public interface JobTypeFailureHandler<T> extends TypedJobFailureHandler<T> {
  JobType<T> type();
}

public interface FailedJob {
  UUID id();
  String type();
  String payload();              // raw JSON, like Job.payload()
  <T> T payload(Class<T> type);
  JobState state();              // FAILED or COMPENSATION_FAILED: the state the job ends in
  String error();                // the job's last_error
}
```

`FailedJob` is an interface because `payload(Class)` needs the node's `JsonMapper`; it has no
steps, waits or sleeps. Registering again for a type throws, like `registerJobHandler` (§10.1).
The typed variant
mirrors `registerJobHandler(JobType, TypedJobHandler)`: the payload arrives mapped (`null` if the
job has none), e.g.

```java
bikesched.registerJobFailureHandler(CHARGE, (failed, charge) ->
    deadLetters.insert(failed.id(), charge.orderId(), failed.error()));
```

`JobTypeFailureHandler` mirrors `JobTypeHandler` for class-based handlers. One class can implement
both (same `type()`), and it is still registered twice, explicitly:

```java
class ChargeHandler implements JobTypeHandler<Charge>, JobTypeFailureHandler<Charge> {
  public JobType<Charge> type() { return CHARGE; }
  public void handle(Job job, Charge charge) { payments.charge(charge); }
  public void handle(FailedJob failed, Charge charge) { deadLetters.insert(failed.id(), charge); }
}

var handler = new ChargeHandler();
bikesched.registerJobHandler(handler).registerJobFailureHandler(handler);
```

The job builders (`JobBuilder`, `TransactionalJobBuilder`) get the counterpart of `handler(…)` in
`JobHandlerSettings`, with the same semantics: registered when the job is scheduled, and **an
already registered failure handler for the type is kept** (so the same scheduling code can run
repeatedly), unlike `registerJobFailureHandler`, which throws then:

```java
// JobHandlerSettings<B, P>
B failureHandler(JobFailureHandler handler);
B failureHandler(TypedJobFailureHandler<? super P> handler);

bikesched.newJob(CHARGE)
    .handler((job, charge) -> payments.charge(charge))
    .failureHandler((failed, charge) -> deadLetters.insert(failed.id(), charge))
    .payload(charge)
    .schedule();
```

`StepJobBuilder` (child jobs) gets none, as it has no `handler(…)`: a child job type registers its
handlers on its own.

New job state `FAILING`, like `COMPENSATING`, with two columns:

```sql
failing_state    text,                    -- FAILED or COMPENSATION_FAILED, only while FAILING
failure_attempts integer not null default 0
```

- The statements ending a job permanently (`FAIL_JOB_SQL`, `COMPLETE_COMPENSATION_SQL` when it
  ends `FAILED`, `FAIL_COMPENSATION_SQL`) get a parameter "the type has a failure handler on this
  node". If set, the job becomes `FAILING`, due now, instead of ending. Types without a failure
  handler behave as before.
- Compensations run first, so the handler sees the final state.
- Claiming a `FAILING` job increments `failure_attempts` and runs only the failure handler, within
  the job's timeout and the hard timeout. Returning ends the job in `failing_state`.
- A handler that throws or times out is retried after the node's `RetryBackoff`. After
  `failureHandlerAttempts` runs the job ends in `failing_state` anyway, logged at `ERROR`;
  `last_error` keeps the original error.
- Releasing jobs (dead node, abandoned attempt) puts a `FAILING` job back to `FAILING`.
- A node claiming a `FAILING` job without a failure handler for its type (removed in a deploy)
  ends it in `failing_state` and logs a warning.
- Child jobs run their failure handler too. Everything that happens when a job ends moves to the
  end of `FAILING`: the parent's child-job step completes, the next recurring occurrence is
  scheduled, `finished_at` is set.
- `cancel(jobId)` does not apply to `FAILING` jobs; `retry(jobId)` only to `FAILED` ones, as
  today.

### 4.3 Step failure handlers

```java
// org.bikesched.job
@FunctionalInterface
public interface StepFailureHandler<T> {
  void handle(FailingStep<T> step) throws Exception;
}

public interface FailingStep<T> {
  UUID jobId();
  String name();
  String error();                // the step's last_error
  void resolve(T result);        // the step succeeds with this result, the job continues
}

// StepBuilder<T>
StepBuilder<T> onFailure(StepFailureHandler<T> handler);
```

- When it runs is unchanged: the step threw in the job's last attempt, it becomes `FAILING`, the
  handler runs in the same attempt, and again in an extra job attempt if it throws or the node
  dies.
- New step column `failure_attempts` counts the handler's runs; after `failureHandlerAttempts`
  the step becomes `FAILED` and the job fails (replaces today's unlimited extra attempts).
- `resolve(result)` is a flag checked after the handler returns, like `job.cancel()`. The step
  becomes `SUCCEEDED` with the result, stored and replayed like any step result, and `run(…)`
  returns it. The job history records it as "resolved by its failure handler". Resolving and then
  throwing: the resolve wins, with a warning.
- A resolved step is not compensable, even with `compensateWith`: its side effect failed, the
  fallback value has none to undo.
- Without `resolve` the step becomes `FAILED` and the job fails, as today.
- The handler gets the stored error on every run, also the first: one behaviour instead of
  "exception now, text after a crash".
- Kotlin: `step<T>(name, onFailure = { failing -> … })`, `failing.resolve(x)` typed by `T`.

### 4.4 Child job results

A failed child job is resolved by its parent, not by the child's failure handler.
`JobResult<T>` gets `T orElse(T fallback)` next to `orElseThrow()`: the result if `Succeeded`,
otherwise the fallback (`Failed`, `Cancelled`, `CompensationFailed`, `TimedOut`):

```java
Receipt receipt = job.waitForJob(charge, Receipt.class).orElse(Receipt.NONE);
```

The child's failure handler still runs before the parent continues (§4.2).

## 5. Rejected alternatives

- Running the job failure handler right after the failed attempt with the real exception, falling
  back to a later claim after a crash (today's step model): two paths, runs outside the attempt's
  timeout, and cannot run after compensation, which needs its own claim.
- Serializing exceptions: classes may not exist on the reading node; causes and stack traces do
  not fit a JSON column.
- A flag column instead of the `FAILING` job state: claiming, releasing and the lock CHECK key on
  the state, as for `COMPENSATING`.
- The step failure handler returning the fallback (`T handle(…)`): every handler would need a
  value just to fail; a flag follows the `job.cancel()`/`job.fail()` convention.
- A child job's failure handler resolving it with a result: the job's outcome would no longer be
  final, only for child jobs; the fallback belongs to the parent (two parents may want different
  ones), and the child's state would misreport it (`FAILED` with a `Succeeded` result, or
  `SUCCEEDED` with a failure).

## 6. Testing

- `JobFailureHandlerTest`: runs once and the job ends `FAILED` after an exception, a timeout, an
  abandoned attempt, a dead node and `fail(…).remainingAttempts(0)`; after compensation with
  `FAILED` and `COMPENSATION_FAILED`; not for retried attempts, cancelled jobs or types without a
  handler; a throwing handler is retried with backoff and given up after `failureHandlerAttempts`;
  a node without the handler ends a `FAILING` job; the parent continues and the next recurring
  occurrence is scheduled only after the handler ran; `FailedJob` fields; the typed variant gets the
  mapped payload (and `null` without one); a class implementing `JobTypeHandler` and
  `JobTypeFailureHandler` registers both; `failureHandler(…)` on the job builders registers at
  `schedule()` and keeps an already registered one; history and log lines.
- `StepTest`: `resolve` continues the job with the result and a replay returns it; without it the
  job fails; the handler gets the stored error; a throwing handler is given up after
  `failureHandlerAttempts`; resolve wins over a throw; a resolved step with `compensateWith` is not
  compensated. Kotlin `onFailure` with `resolve`.
- `ChildJobTest`: `orElse` returns the result of a succeeded child and the fallback for each other
  outcome.
- `SchemaMigratorTest` (new columns), `DueJobsWritePathsTest` (new statements), listener outcomes
  (`FAILING`) in `bikesched-micrometer`.

## 7. Implementation steps

1. `failureHandlerAttempts` and the step rework (`FailingStep`, `resolve`, attempt limit) with
   Kotlin, the shop example and `docs/steps.md`; `JobResult.orElse` with its docs.
2. Job failure handlers: schema, `JobState.FAILING`, `JobStore` statements, `JobProcessor`,
   `JobRegistry`, tests.
3. Listener outcomes, history, metrics.
4. Docs (`jobs.md`, `configuration.md`, `database.md`), ADR; remove the `IDEAS.md` entries "Hook
   for permanently failed jobs" and "Job-level failure handler".

## 8. Open questions

None.

## 9. Decisions

- Incidents are on hold ([spec](incidents.md)); job failure handlers cover dead-letter tables and
  alerts.
- Handler runs are limited by `failureHandlerAttempts`, default 3, for jobs and steps.
- A job's outcome is final once `FAILING`; only step failure handlers can recover, with `resolve`.
- Child jobs run their failure handler before their parent continues; their parent, not their
  failure handler, decides on a fallback (`JobResult.orElse`, §4.4).
- Both handlers get the error as text, never an exception.
- Resolved steps are not compensated (§4.3).
- Typed and class-based job failure handlers (`TypedJobFailureHandler`, `JobTypeFailureHandler`)
  next to the string-based one, like the job handlers. Registering a `JobTypeHandler` does not
  register its failure handler implicitly (§4.2).
- The job builders get `failureHandler(…)`, like `handler(…)`: registered at `schedule()`, an
  existing failure handler is kept; `StepJobBuilder` gets none (§4.2).

## 10. Decided while implementing

1. `registerJobFailureHandler` throws for a type that already has a failure handler instead of
   replacing it: `registerJobHandler` throws too, and a second registration is usually a bug. The
   builders' `failureHandler(…)` keeps an existing one, like `handler(…)`.
2. The compensation replay registers compensations only for compensable steps
   (`StepStore.CompletedStep.compensable()`), so a resolved step is skipped even when another step
   triggers the compensation.
3. The statements ending a running job (`COMPLETE_JOB_SQL`, `FAIL_JOB_SQL`, `CANCEL_JOB_SQL`, the
   compensation statements) and `REQUEST_CANCEL_SQL` exclude a claim running the failure handler
   (`failing_state is null`); only `END_FAILING_SQL` and `RETRY_FAILURE_HANDLER_SQL` end it.
4. A cancelled job whose compensations fail permanently ends `COMPENSATION_FAILED`, so its failure
   handler runs too; a plain cancellation does not.
5. A step failure handler gets the truncated stored text on its first run too
   (`ValidationUtil.truncateMessage`), so every run sees the same `error()`.
6. `JobClaim` gets the component `failing` (next to `compensation`); for a failing claim `attempt`
   counts the runs of the failure handler.
