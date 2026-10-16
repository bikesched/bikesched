# Specification: child jobs and job results (`job.newJob`, `job.waitForJob`)

Status: **implemented** (decisions in §9, deviations in §10); user documentation in
[docs/child-jobs.md](../multi-step-jobs/child-jobs.md), decision in
[ADR 0029](../decisions/0029-child-jobs-as-steps.md).

## 1. Goal

A job handler starts other jobs exactly once, as durable steps, and can wait for them to end
without holding a thread. Jobs can have a result, which the waiting parent receives together with
the child's outcome:

```java
bikesched.registerJobHandler("fulfil-order", job -> {
    Order order = job.payload(Order.class);
    UUID charge = job.newJob(CHARGE).payload(order.payment()).stepName("charge").schedule();
    UUID pack = job.newJob(PACK).payload(order.items()).stepName("pack").schedule();

    // both children run in parallel; the parent waits for each without a thread
    Receipt receipt = switch (job.waitForJob(charge, Receipt.class)) {
        case JobResult.Succeeded<Receipt>(var id, Receipt r) -> r;
        case JobResult.Failed<Receipt> f -> { job.cancel("payment failed: " + f.error()); yield null; }
        case JobResult.Cancelled<Receipt> c -> { job.cancel(c.message()); yield null; }
        case JobResult.CompensationFailed<Receipt> c -> throw new IllegalStateException(c.error());
        case JobResult.TimedOut<Receipt> t -> throw new IllegalStateException("no timeout set");
    };
    if (receipt == null) return;
    Parcel parcel = job.waitForJob(pack, Parcel.class).orElseThrow();
    job.complete(new Fulfilment(receipt, parcel));   // this job's own result
});
```

## 2. Today

| Approach | What goes wrong |
|---|---|
| `bikesched.newJob(…)` in a handler, outside a step | scheduled again on every run: after each event wait, sleep or retry |
| the same inside a step | at least once: the child commits in its own transaction; if the node dies before the step's result is stored, a second child is scheduled |
| the child publishes an event at its end, the parent waits for it | never arrives when the child fails, is cancelled or crashes before the publish; every pair needs its own event name |
| a result | jobs have none; a caller or parent only sees the state (`findJob`) |

## 3. Non-goals

- Recurring jobs as children or as parents (§4.9).
- Deadlock detection (§4.10).
- Starting children from compensations (like publishing events from them, deferred).
- Starting a child in the caller's transaction (`connection(…)`): the parent's step transaction
  is the transaction.
- Blocking waits for a job from outside a job (`bikesched.awaitJob(id)`): callers poll `findJob`,
  which now returns the result.
- Results from annotated handlers (`@JobHandler` methods returning a value) and the Spring
  starter: a follow-up on top of `Job.complete`.
- A result type on `JobType` (`JobType<P, R>`): it would change the existing record; the result
  class is passed to `waitForJob` instead.
- Limits on nesting depth or children per job: a runaway job tree is a handler bug; documented.

## 4. Design

Core only (`org.bikesched.job`, `internal.job`, `internal.database`, `V001`), Kotlin extensions in
`bikesched-kotlin`. No new dependencies.

### 4.1 Job results

The handler sets the result with a flag, like `cancel()`/`fail()`, so `JobHandler` stays `void`:

```java
void complete(Object result);   // on Job; mapped by the JsonMapper
```

- Stored in a new column `bikesched_jobs.result jsonb`, written only in the transaction that ends
  the job `SUCCEEDED`. A later failure or cancellation of the attempt discards it; the next
  attempt sets it again.
- `JobInfo.result(Class<T>)` returns `Optional<T>` (empty unless `SUCCEEDED` with a result).
- Calling `complete` twice: the last value wins (the handler is replayed, so it may run again).
- Precedence within an attempt: cancelling wins over failing (as today), failing wins over
  `complete`; an exception thrown after `complete` fails the attempt. A job ending `FAILED` or
  `CANCELLED` after its compensations has no result.
- Job history: "succeeded with result" without the result itself (it may be large or
  sensitive); the same for the child's outcome on the parent's step.
- The child's node maps the result with its `JsonMapper`, the parent's node reads it with its own:
  when parent and child types run in different services, the mappers must agree on the format
  (documented).

### 4.2 One step per child

Starting a child and receiving its outcome are the same step (`kind = 'CHILD_JOB'`):

| Step state | Meaning |
|---|---|
| `WAITING` | the child was scheduled and has not ended |
| `SUCCEEDED` | the child ended; `child_state`, `result` and `last_error` hold its outcome |
| `CANCELLED` | the parent was cancelled while the child ran (existing cancel path); the child is not touched |
| `TIMED_OUT` | the parent's wait timed out before the child ended (§4.4); the child is not touched |
| `COMPENSATED` | the step's compensation ran (§4.8) |

Unlike an event wait or a sleep, a `WAITING` child step does not suspend the job: the handler
continues and suspends only in `waitForJob` (§4.4). `WAITING` therefore no longer implies that the
job is suspended at that step.

### 4.3 Starting a child

`Job.newJob(type)` / `newJob(JobType<T>)` return a `StepJobBuilder<P>` (extends `JobSettings`, the
settings shared by all job builders): `payload`, `priority`, `maxAttempts`, `timeout`, plus `stepName(…)` and
`compensateWith(…)`, ending in `schedule()`, `schedule(Instant)`, `schedule(Duration)`, which
return the child's id. It has no `connection(…)` and no `schedule(Recurrence)`: children are never
recurring, enforced at compile time. It has no `handler(…)` either (see §10).

- One transaction, fenced by the job lock (`StepStore.requireJobLock`): insert the child with
  `parent_job_id` = the parent, insert the step `WAITING` with `child_job_id` and `child_job_type`, record history for
  both. Either both exist or neither does.
- Later runs find the step (in any state) and return its `child_job_id` without scheduling; a
  different job type is rejected, like a wait for another event.
- Default step name `start <type>` (rejected with a hint to `stepName(…)` if it exceeds 200
  characters); a second start with the same default name in one run throws, as for publishes.
- Rejected inside a step handler (`StepGuard`).

### 4.4 Waiting for a child

```java
<T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType);
<T> JobResult<T> waitForJob(UUID childJobId, Class<T> resultType, Duration timeout);
```

- No step name: the wait reads the child's step, found by `child_job_id` among the job's own
  steps. An id that is not one of the job's children → `IllegalArgumentException`. This keeps waits
  between jobs a tree (§4.10).
- `SUCCEEDED`: returns the `JobResult` right away, also on every replay.
- `WAITING`: the attempt ends with a `JobSuspension(stepName)` (a third variant of the sealed
  `Suspension`), the job is `WAITING` without a deadline: no attempt used, no thread, no slot.
- With a timeout: the first wait stores the deadline in the step's `wait_until` (`now() +
  timeout`, database time; the stored deadline wins on later runs, as for sleeps) and suspends
  with `scheduled_at` = the deadline. A run after the deadline with the step still `WAITING` marks
  it `TIMED_OUT` (fenced, `state = 'WAITING'`, so it races cleanly with the child's end) and
  returns `TimedOut`, also on every later run. The child keeps running; its end no longer
  changes the step. The parent decides whether to cancel it (`bikesched.cancel(childId, …)`).
- `CANCELLED`/`COMPENSATED` can only be met during cancellation or compensation, where the replay
  ends at the wait (`CompensationReplayEnded`).
- Several children: start them all, then wait one after another; children that already ended
  return right away, so the total time is that of the slowest child.
- Kotlin: `job.startJob(type, payload, stepName, …)` and `job.awaitJob<T>(childJobId, timeout)`
  (reified, optional `kotlin.time.Duration` timeout, returns `JobResult<T>`), like `awaitEvent`.

### 4.5 `JobResult`

A sealed interface in `org.bikesched.job`, one record per state a child ends in, plus the timed out
wait:

```java
public sealed interface JobResult<T> {
  UUID jobId();
  record Succeeded<T>(UUID jobId, T result) implements JobResult<T> {}  // result may be null
  record Failed<T>(UUID jobId, String error) implements JobResult<T> {}
  record Cancelled<T>(UUID jobId, String message) implements JobResult<T> {}
  record CompensationFailed<T>(UUID jobId, String error) implements JobResult<T> {}
  record TimedOut<T>(UUID jobId) implements JobResult<T> {}   // the wait, not the child, ended

  default boolean isSucceeded() { … }
  /** The result if succeeded, otherwise throws a {@code JobFailedException} with the outcome. */
  default T orElseThrow() { … }
}
```

- A failed child never fails the parent by itself; the parent decides (`switch`, `orElseThrow`).
- `FAILED`/`CANCELLED` after the child's compensations ran are `Failed`/`Cancelled`; no
  `Compensated` variant for now (a flag can be added later without breaking `switch`es).

### 4.6 Storage

`bikesched_jobs`:

- `result jsonb` (§4.1).
- `parent_job_id uuid`, set exactly for children; no foreign key, like `event_id`: the parent may
  be deleted after its retention while the child still runs, and the child stays recognizable as
  a child. Deleting jobs doesn't touch their children's rows. No index: nothing looks children up
  by their parent (add one with cancellation propagation, if ever).
- No job kind column: an occurrence is `recurrence is not null`, a child `parent_job_id is not
  null`, every other job neither; the two columns are independent, so a `kind` would only repeat
  them (unlike on steps, where kinds share columns).

`JobInfo.parentJobId()` returns `Optional<UUID>`.

`bikesched_steps`:

- `child_job_id uuid`, set exactly for `CHILD_JOB` steps; no foreign key, like `event_id`: the
  child is deleted after its retention while the step lives as long as its job.
- `child_job_type text`, the child's type, set exactly for `CHILD_JOB` steps (CHECK 1 to 200
  characters): replays compare it with the requested type, also after the child was deleted.
- `child_state text`: the child's terminal state, set exactly when a `CHILD_JOB` step is
  `SUCCEEDED`; CHECK `in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'COMPENSATION_FAILED')`. The child's
  result goes into `result`, its error or cancel message into `last_error` (both exist already).
- `kind` CHECK gains `CHILD_JOB`; `wait_check` allows `WAITING` and `wait_until` (the wait's
  deadline) for it; `TIMED_OUT` is allowed for `EVENT_WAIT` and `CHILD_JOB`; the event check keeps
  `event_*` null for it.
- Index `(child_job_id) where kind = 'CHILD_JOB' and state = 'WAITING'`: the child's end finds its
  step.

### 4.7 The child ends

Every path that ends a job in a terminal state (succeeded, failed after the last attempt,
cancelled, compensation ended or failed, cancelled while scheduled or waiting) calls one store
method in the same transaction, if `parent_job_id` is set:

1. Complete the parent's step: `state = 'SUCCEEDED'`, `child_state`, `result`, `last_error`,
   `finished_at`, where `child_job_id` = the child and `state = 'WAITING'` (a `CANCELLED` or
   `TIMED_OUT` step stays as it is).
2. If a step was completed and the parent is `WAITING`, make it due (`scheduled_at = now()`), as
   `RESUME_WAITING_STEPS_SQL` does for events; a `RUNNING` parent is left alone.
3. History: on the parent, step `SUCCEEDED` ("job `<id>` ended `FAILED`") and "resumed by job …".

The step is updated by the child's transaction without the parent's lock, like a publish completes
other jobs' waits.

**Spurious wake-ups.** A `WAITING` parent may be suspended somewhere else (a sleep, an event wait,
another child). Made due, it replays, finds that step unfinished and suspends again (the sleep
already handles being claimed early). One extra replay per ended child is accepted instead of
storing on the job where it is suspended (§9, decision 6).

**Race with the parent's suspension.** The step is `WAITING` from the start, but the parent
suspends in a later transaction than the one `waitForJob` read the step in; if the child ends in
between, step 2 finds the parent `RUNNING` and leaves it alone. Events solve this with an advisory
lock on name and key; here the suspension transaction locks the child step's row (`for update`)
and rechecks it: if it is no longer `WAITING`, the job is made due instead of suspended. The
child's end updates the same row, so the two serialize.

### 4.8 Cancellation and compensation

- Cancelling the parent: its `WAITING` child steps become `CANCELLED` (existing path for
  unfinished steps); the children are not touched.
- The child step takes an optional compensation, `compensateWith((step, childId) -> …)`, the
  existing `CompensationHandler<UUID>` with the child's id, e.g. to cancel the child
  (`bikesched.cancel(childId, "order cancelled")`) or start an undoing job. Without one, the child
  is left alone.
- Compensation replay: today an unfinished step ends the replay before its compensation is
  registered. A child step counts as completed for this once the child was started, so `WAITING`,
  `CANCELLED` and `TIMED_OUT` child steps return their id and register their compensation, like `SUCCEEDED`
  ones: a running child is exactly the case worth compensating. `waitForJob` on such a step ends
  the replay.
- The compensation can't wait for the child to finish cancelling (waits are rejected in
  compensations): cancelling is fire-and-forget. The handler can ask `findJob` for the child's
  state.
- A child cancelled from elsewhere reaches a waiting parent as `Cancelled`.

### 4.9 Recurring jobs

- Occurrences of recurring jobs can't start children: `job.newJob` throws
  `IllegalStateException` in an occurrence (checked at runtime; the handler has no compile-time
  knowledge of being recurring). An occurrence is replaced by the next one and has no single
  caller interested in its children.
- Children can't be recurring: `StepJobBuilder` has no `schedule(Recurrence)` (§4.3).

### 4.10 Deadlocks

Not detected. A general detector would need the wait-for graph, and event waits don't declare
who will publish: any job, a webhook or code outside bikesched can. What the design does instead:

- Waits between jobs can't form a cycle: a job waits only for its own children, and a child is
  created by its parent, so job waits form a tree.
- Cycles through events (a child waits for an event only its waiting parent would publish) remain
  possible. They show as `WAITING` jobs in `findJob` and the history ("waiting for job …",
  "waiting for event …"); a wait timeout (§4.4) bounds them.

### 4.11 Interplay with existing features

| Feature | Behaviour |
|---|---|
| Attempts | waiting does not use up an attempt (as event waits and sleeps) |
| Job timeout | covers the running attempt only; the wait ends the attempt |
| Parent retried after a failure | the child step returns the same child; a `COMPENSATED` one starts a new child, as compensated steps run again |
| `bikesched.retry(childId)` | rejected (`IllegalArgumentException`), like occurrences: the parent may already have the outcome and replays it; to try again, the parent starts a new child under a new step name |
| Job retention | the child may be deleted after it ended; its outcome stays on the parent's step |
| Parent deleted (retention) | `parent_job_id` keeps the deleted parent's id; the child's end finds no step and updates nothing |
| Parent ends without waiting | allowed (fire-and-forget); the child's end still completes the step |
| Job history | child scheduled "started by job `<parent>` step `<name>`"; parent suspended "waiting for job …", resumed "resumed by job …" |
| Nested children | allowed: a child can start its own children |

### 4.12 Rejected alternatives

- **Separate start and wait steps** (`JOB_START` + `JOB_WAIT` with its own name): the wait step
  adds a name, a kind and a second row per child without new information; the start step can hold
  the outcome itself.
- **A job kind column** (`ROOT`/`CHILD`/`OCCURRENCE` or an `origin`): repeats `recurrence` and
  `parent_job_id`; only needed if the parent reference were cleared on deletion.
- **`parent_job_id` as a foreign key** (`on delete set null`): deleting a parent would update its
  children and clear the reference that marks them as children, and the key needs an index.
- **Child outcome via an internal event** (`job-ended/<id>`): reuses event waits, but events
  expire after their retention, the outcome would live in an event payload, and failed or crashed
  children need the same end-of-job hook anyway.
- **An envelope in `result`** (`{"state", "result", "error"}`) instead of `child_state`: keeps the
  schema smaller but hides the state from SQL and mixes bikesched's format with the user's JSON.
- **A result returned from the handler** (`ResultJobHandler<T>`): a second handler type for every
  registration path (Java, typed, annotations, Kotlin, Spring); a flag fits `cancel()`/`fail()`.
- **Throwing from `waitForJob` on a failed child**: control flow by exception, and the parent's
  retries would replay the same failed outcome anyway.

## 5. Documentation

A section "Child jobs" (in `docs/jobs.md` or its own page), the `Job` method table, `JobInfo`
results, `docs/kotlin.md`, `docs/database.md` (new columns, kind, indexes). ADR: child jobs as one
step holding the outcome, `JobResult` as a sealed type, no deadlock detection. The docs also
cover: compatible `JsonMapper`s across services (§4.1), no limit on nesting (runaway trees are
handler bugs), the extra replay when a child ends while the parent waits elsewhere (§4.7).

## 6. Testing

Core (`AbstractBikeschedTest`, a new `ChildJobTest`):

1. `complete(result)` stores the result on success; `fail()`/`cancel()`/an exception after
   `complete` discards it; no result after compensation; `findJob(…).result(Class)`; the history
   records no result content.
2. Start a child: one child per replay (suspend/resume and retry), same id returned; atomicity (the
   child doesn't exist if the step transaction fails).
3. Wait for a child that succeeds, fails, is cancelled, ends `COMPENSATION_FAILED`: the matching
   `JobResult` variant, the parent resumed.
4. A child that ended before the wait: no suspension.
5. Race: the child ends between the parent's read of the step and its suspension (forced): the
   parent is made due, not left `WAITING`.
6. Two children in parallel, waited one after the other; the second ending while the parent waits
   for the first: one spurious replay, then the parent suspends again.
7. Outcome survives the child's deletion (retention) and a parent sleeping between start and wait.
8. Waiting for a job that is not a child → `IllegalArgumentException`; occurrence starting a child
   → `IllegalStateException`; start inside a step handler rejected.
9. Parent cancelled while the child runs: step `CANCELLED`, child untouched and its end leaves the
   step `CANCELLED`; with a compensation: it receives the child's id and cancels it.
10. Parent deleted after its retention while the child runs: `parent_job_id` unchanged, the child
    ends normally; `findJob(child).parentJobId()`.
11. Wait timeout: `TimedOut` returned and stable across replays, the step `TIMED_OUT` with the
    deadline unchanged by a different argument; the child's later end leaves the step as it is;
    the child ending right at the deadline yields exactly one of the two outcomes.
12. `bikesched.retry(childId)` → `IllegalArgumentException`.
13. History entries and log lines.

Kotlin: `startJob` and `awaitJob<T>` (with and without timeout) end to end with data classes.

## 7. Implementation steps

1. Job results: column, `Job.complete`, `JobInfo.result`, test 1.
2. Schema: `parent_job_id` (and `JobInfo.parentJobId`), `CHILD_JOB` kind and columns, constraints, indexes,
   `SchemaMigratorTest`.
3. `StepJobBuilder` and `job.newJob` as a step (guard, duplicates, compensation replay), tests 2, 8.
4. The end-of-job hook in every terminal path; `JobSuspension`, `waitForJob`, `JobResult`; tests
   3–7, 9–13.
5. Kotlin extensions; docs; ADR; remove the entry from `IDEAS.md`.

## 8. Effort

Large: a new step kind, a hook in every terminal path of a job, a new suspension variant, a
compensation replay change, a wait timeout and a new public result type, plus job results on
their own.

## 9. Decisions (former open questions)

1. `bikesched.retry(childId)` is rejected like for occurrences; a parent that wants another try
   starts a new child under a new step name.
2. `waitForJob` has an overload with a timeout, stored in the step's `wait_until`; it returns a
   `TimedOut` variant instead of calling a timeout handler (§4.4).
3. No `Compensated` variant for now; `Failed`/`Cancelled` cover compensated children.
4. Names: `Job.complete(result)` (`result(value)` reads like a getter), `waitForJob` in Java like
   `waitForEvent`, `awaitJob<T>` in Kotlin like `awaitEvent`.
5. No limit on nesting depth or children per job; documented.
6. Spurious wake-ups are accepted; storing the step a job is suspended at is revisited if fan-out
   with many children turns out to be common.
7. Precedence: cancel over fail over `complete`; jobs ending after compensation have no result.
8. No result type on `JobType` for now; the result class is passed to `waitForJob`.
9. Results from `@JobHandler` return values and the Spring starter are a follow-up (non-goal).
10. The job history records that a result exists, not its content (also for the child's outcome).
11. Different services' `JsonMapper`s must be compatible; documented, not checked.

## 10. Implementation notes

Choices the spec left open, made while implementing:

1. A child's type needs a handler registered on the parent's node. The `StepJobBuilder` has no
   `handler(…)` (decided in review): starting a child is a lightweight way to connect job types
   registered on their own, and inline code belongs in a step. The handler setters moved from
   `JobSettings` into `JobHandlerSettings`, which only `JobBuilder` and `TransactionalJobBuilder`
   extend.
2. The step stores the child's type (`child_job_type`, decided in review): starting a child under a
   step name that started a child of another type is rejected, also after the child was deleted.
3. A child's end makes a `WAITING` parent `SCHEDULED` and due now, like a published event does.
4. The end-of-job hook is two statements after the terminal update in the child's transaction
   (complete the step, then resume the parent): the second needs a fresh snapshot after waiting for
   a parent that locked the step while suspending (§4.7). Terminal writes of children therefore
   always run in a transaction; jobs without a parent keep their single statement.
5. A wait without a timeout followed by one with a timeout (on a later run) stores the deadline
   then: "the first wait with a timeout stores the deadline".
6. A child step with a compensation counts as compensable in `WAITING`, `CANCELLED`, `TIMED_OUT`
   and `SUCCEEDED`; compensating it keeps the child's outcome (`child_state`, `last_error`).
7. Schema checks: `result` only on `SUCCEEDED` jobs; `parent_job_id` and `recurrence` exclusive.
8. Kotlin `startJob` has no handler argument (handlers come from `handleJobs`); the Kotlin wait is
   `awaitJob<T>(childJobId, timeout)`.
