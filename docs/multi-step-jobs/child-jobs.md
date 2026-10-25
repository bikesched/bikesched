---
title: "Child jobs and job results"
weight: 3
---

# Child jobs and job results

A handler can start other jobs exactly once, as durable steps, and wait for them to end without
holding a thread. Jobs can have a result, which the waiting parent receives with the child's
outcome.

```java
static final TypedJob<Payment> CHARGE = TypedJob.of("charge", Payment.class);
static final TypedJob<Items> PACK = TypedJob.of("pack", Items.class);

bikesched.registerJobHandler(CHARGE, (job, payment) -> job.complete(payments.charge(payment)));
bikesched.registerJobHandler(PACK, (job, items) -> job.complete(warehouse.pack(items)));

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

## Job results

`job.complete(result)` sets the job's result, mapped to JSON by the `JsonMapper` right away. Like
`cancel()` and `fail()` it is a flag read after the handler returned, so the handler stays `void`:

- The result is stored (`bikesched_jobs.result`) only in the transaction that ends the job
  `SUCCEEDED`; the last call wins.
- Cancelling wins over failing, failing wins over `complete`: an attempt that is cancelled, failed
  or throws after `complete` discards the result, the next attempt sets it again. A job that ends
  after its compensations has no result.
- `bikesched.findJob(id)` returns it: `JobInfo.result(Class)` is `null` unless the job `SUCCEEDED`
  with a result.
- The [job history](../jobs/README.md#job-history) records "succeeded with result", never the result
  itself (it may be large or sensitive).

## Starting children

`job.newJob(name)` and `job.newJob(typedJob)` return a `StepJobBuilder` with the usual settings
(`payload`, `priority`, `maxAttempts`, `timeout`) plus:

| Method | Purpose |
|---|---|
| `stepName(name)` | the step's name, default `start <job name>`; set it when a job starts several children with one name |
| `reference(reference)` | the child's [reference](../jobs/README.md#references), default the parent's; `null` for none |
| `compensateWith((step, childId) -> …)` | undo starting the child if the parent is cancelled or fails permanently, e.g. `bikesched.cancel(childId, "order cancelled")` |
| `schedule()`, `schedule(Instant)`, `schedule(Duration)` | start the child and return its id |

- The first run stores the child (type `CHILD`, with `parent_job_id`) and a `CHILD_JOB` step of the
  parent in one transaction, fenced by the parent's lock: either both exist or neither does. Later
  runs find the step and return the same child's id without scheduling again; a step that started a
  child with another name is rejected (the step stores the child's name, so also after the child was
  deleted).
- There is no `connection(…)` (the parent's step transaction is the transaction) and no
  `schedule(Recurrence)`: children are never recurring. Occurrences of recurring jobs cannot start
  children (`IllegalStateException`): the next occurrence replaces them, so nobody waits for their
  children.
- Starting a child is rejected inside a step handler, like any nested step.
- The child's name needs a handler registered on the parent's node. There is no `handler(…)`:
  children connect jobs registered on their own; code that runs inline belongs in a
  [step](steps.md).

## Waiting for children

```java
JobResult<Receipt> result = job.waitForJob(chargeId, Receipt.class);
JobResult<Receipt> result = job.waitForJob(chargeId, Receipt.class, Duration.ofHours(1));
```

- The wait needs no step name: it reads the child's step. An id that is not one of the job's own
  children is rejected (`IllegalArgumentException`), so waits between jobs always form a tree.
- If the child has ended, the outcome is returned right away, on every run. Otherwise the attempt
  ends there and the parent is `WAITING` without a deadline: no attempt used, no thread, no slot.
  When the child ends, its transaction stores the outcome with the parent's step and makes a waiting
  parent due; the parent's handler runs again from the start and the wait returns.
- With a timeout, the first wait stores its deadline (`wake_at`, database time; the stored one
  wins over a changed argument). Once it has passed with the child still running, the step is
  `TIMED_OUT` and the wait returns `JobResult.TimedOut`, also on every later run. The child keeps
  running and its end no longer changes the step; cancel it with `bikesched.cancel(childId, …)` if
  needed.
- To wait for several children, start them all first, then wait for one after the other: children
  that already ended return right away, so the total time is that of the slowest child.
- A child that ends while the parent waits for something else (a sleep, an event, another child)
  makes the parent due anyway: it replays, finds its wait unfinished and suspends again. Expect one
  extra replay per child ending early.

### Outcomes

`JobResult<T>` is a sealed interface, one record per way a child ends:

| Record | Child | Fields |
|---|---|---|
| `Succeeded` | `SUCCEEDED` | `jobId`, `result` (may be `null`) |
| `Failed` | `FAILED`, also after its compensations | `jobId`, `error` |
| `Cancelled` | `CANCELLED`, also after its compensations | `jobId`, `message` |
| `CompensationFailed` | `COMPENSATION_FAILED` | `jobId`, `error` |
| `TimedOut` | still running when the parent's wait timed out | `jobId` |

A failed child never fails its parent by itself: the parent decides, with a `switch`, with
`orElseThrow()`, which returns the result or throws a `JobFailedException` carrying the outcome,
or with `orElse(fallback)`, which returns the fallback for every outcome but `Succeeded`.
`isSucceeded()` tells success apart.

## Cancellation, compensation and retries

| Situation | Behaviour |
|---|---|
| parent cancelled while the child runs | the parent's step is `CANCELLED`, the child is not touched and its end leaves the step as it is |
| step with `compensateWith` | the compensation receives the child's id, also while the child still runs (the case worth compensating); cancelling it is fire-and-forget, the compensation can't wait for it |
| child cancelled from elsewhere | the waiting parent receives `Cancelled` |
| parent retried after a failure | the step returns the same child; a `COMPENSATED` step starts a new child, as compensated steps run again |
| `bikesched.retry(childId)` | rejected (`IllegalArgumentException`): the parent may have the outcome already; to try again, the parent starts a new child under a new step name |
| parent ends without waiting | allowed (fire-and-forget); the child's end still completes the step |
| child deleted after its retention | its outcome stays with the parent's step |
| parent deleted after its retention | `parent_job_id` keeps the deleted parent's id; the child ends normally |
| nested children | allowed: a child can start its own children |

## Limits and caveats

- **No limit on nesting depth or children per job**: a runaway job tree is a handler bug.
- **No deadlock detection**: a job waits only for its own children, so waits between jobs cannot
  form a cycle. Cycles through events remain possible (a child waits for an event only its waiting
  parent would publish); they show as `WAITING` jobs in `findJob` and the job history, and a wait
  timeout bounds them.
- **Compatible `JsonMapper`s**: the child's node maps the result with its mapper, the parent's
  node reads it with its own; when parent and child types run in different services, the mappers
  must agree on the format.
- **Job history**: the child is "started by job … step …", the parent's step "started job …", the
  parent "suspended at step …, waiting for job …"; the child's end completes the step ("job …
  ended FAILED") and resumes the parent ("resumed by job …").
