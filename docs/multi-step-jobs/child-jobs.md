---
title: "Child jobs and job results"
weight: 3
---

# Child jobs and job results

A handler can start other jobs exactly once, as durable steps, and wait for them to end without
holding a thread. Jobs can have a result, which the waiting parent receives with the child's
outcome. Many children of one kind start as one [batch](#batches), optionally at most a few at a
time, and the parent waits once for all of them.

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
- The [history](../jobs/README.md#history) records that the job `SUCCEEDED`, never the result
  itself (it may be large or sensitive).

### Result types

A job that returns something can declare its result type next to its payload type. Its handler
returns the result instead of calling `complete`, and parents read it without passing a class:

```java
static final ResultJob<Payment, Receipt> CHARGE =
    TypedJob.of("charge", Payment.class, Receipt.class);

bikesched.registerResultJobHandler(CHARGE, (job, payment) -> payments.charge(payment));

UUID charge = job.newJob(CHARGE).payload(order.payment()).schedule();
Receipt receipt = job.waitForJob(charge, CHARGE).orElseThrow();   // typed by the job
```

- `TypedJob` is a sealed interface: `TypedJob.of(name, payloadType)` returns a `PayloadJob<P>`,
  `TypedJob.of(name, payloadType, resultType)` a `ResultJob<P, R>`. Every API taking a `TypedJob`
  accepts both.
- `registerResultJobHandler` stores the returned value as `job.complete(result)` would, with the
  same precedence: `cancel()` and `fail()` still win. `null` is a result without a value.
- `waitForJob(id, CHARGE)` maps the result with the job's `resultType()`; a batch of a
  `ResultJob` is typed by it.
- `@JobHandler` methods, `StandaloneJobHandler` and the Spring starter keep calling
  `job.complete(result)`.

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
- The child's name needs a handler registered on the parent's node, or has to be one of its
  [remote job names](../jobs/README.md#jobs-other-nodes-handle): the child then runs on a node
  handling it, and the parent waits for it as usual. There is no `handler(…)`:
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

## Batches

A batch starts many children as one durable step: items of one job, each with its own payload and
an optional key, spread over every node handling the job, and the parent waits once for all of
them:

```java
static final ResultJob<Render, Image> RENDER_FORMAT =
    TypedJob.of("render-format", Render.class, Image.class);

bikesched.registerResultJobHandler(RENDER_FORMAT, (job, render) -> images.render(render));

bikesched.registerJobHandler("process-image", job -> {
    UUID imageId = job.payload(UUID.class);
    JobBatch<Image> renders = job.newJobBatch("render", RENDER_FORMAT)
        .addAll(Format.ALL, Format::name, f -> new Render(imageId, f))   // key, payload
        .parallelism(4)                                                  // at most 4 at a time
        .schedule();
    List<Image> images = renders.await().orElseThrow();                  // in item order
    job.complete(new Renditions(images));
});
```

### Starting a batch

| Method | Purpose |
|---|---|
| `job.newJobBatch(stepName, resultJob)` | a batch of a `ResultJob`: payloads and results typed by it |
| `job.newJobBatch(stepName, typedJob)` | a batch of a job without a result type: a `JobBatch<Void>`, every result `null` |
| `job.newJobBatch(stepName, jobName)` | a batch of a job name: payloads mapped as for `newJob(String)`, results read with `get(key, Class)` |
| `job.newJobBatch(stepName)` | a mixed batch: each item names its job (`add(job, key, payload)`, `add(jobName, key, payload)`) |
| `add(payload)`, `add(key, payload)` | one item, keyed by its position (`"0"`, `"1"`, …) or by the key |
| `addAll(payloads)`, `addAll(items, payload)`, `addAll(items, key, payload)` | many items, keyed by position or by a key function |
| `parallelism(k)` | at most `k` items active at once, across all nodes; default no limit |
| `failFast()` | end the batch at the first item that does not succeed and cancel the others (an item busy at that moment, e.g. writing a step, is asked right after) |
| `priority`, `maxAttempts`, `timeout`, `reference` | one value for every item, as for single children |
| `compensateWith((step, item) -> …)` | undo each started item (a `JobBatchItem` with its `key` and `jobId`) if the parent is cancelled or fails permanently |
| `schedule()`, `schedule(Instant)`, `schedule(Duration)` | store the batch and return its `JobBatch` |

- The batch step, every item and their steps are stored in one transaction, fenced by the
  parent's lock, exactly once, with the payloads mapped to JSON then (a payload that cannot be
  mapped is an `IllegalArgumentException` naming its key). Later runs return the stored batch
  whatever the items are now: they are neither checked nor mapped again.
- Keys are 1 to 200 characters and unique within the batch; item steps are named
  `stepName/key`, which no other step of the job may be named. Duplicate keys, more items than the node's
  [`maxJobBatchSize`](../getting-started/configuration.md) (default 1,000), a `parallelism` below
  1 or a job name without a handler (or
  [remote job name](../jobs/README.md#jobs-other-nodes-handle)) are rejected before anything is
  written.
- An item knows its key: `job.batchKey()` in its handler, `JobInfo.batchKey()`, and the MDC key
  `bikesched.batchKey`. It is `null` for jobs outside a batch.
- An empty batch is allowed and ends right away.

### Parallelism and the `HELD` state

With `parallelism(k)`, the first `k` items are due right away and the others are `HELD`: stored
and visible, but never claimed. Whenever an item ends, its transaction admits the next held items
in item order, as many as the limit allows (recorded as `ADMITTED`). Admitted items keep the due
time of the batch, so `schedule(Instant)` delays them too. A held item can be cancelled with
`bikesched.cancel(id, …)`; it counts as ended and admits nothing.

### Waiting for a batch

`JobBatch.await()` and `await(Duration timeout)` suspend the parent like `waitForJob`: no thread,
no slot, and the parent resumes once, when the batch has ended. An item ending while the parent
waits for something else does not wake it, except the batch's last item (one replay). Waiting for
a single item with `waitForJob` is rejected: wait for the batch.

`JobBatchResult` holds every item's `JobResult`, mapped lazily per item:

| Method | Returns |
|---|---|
| `orElseThrow()` | the results in item order, or a `JobFailedException` naming the keys that did not succeed |
| `isAllSucceeded()` | whether every item succeeded |
| `succeeded()`, `unsucceeded()`, `all()` | maps from key to result or outcome, in item order |
| `get(key)`, `get(key, resultJob)`, `get(key, Class)` | one item, typed by the batch, by its job, or by a class |

- A failed item never fails the parent by itself.
- With a timeout, the batch is `TIMED_OUT` once it has passed: held items are cancelled, running
  items keep running, and every unfinished item reads `TimedOut` on every replay.
- With `failFast()`, the first item that does not succeed ends the batch and resumes the parent:
  the other unfinished items read `Cancelled`, held ones are cancelled and running ones get a
  cancel request (they may still finish their work; the parent's view stays `Cancelled`).

### Batches and cancellation

| Situation | Behaviour |
|---|---|
| parent cancelled while the batch runs | the batch step and its waiting item steps `CANCELLED`, held items cancelled, running items not touched unless the compensation cancels them |
| `compensateWith` | each admitted item is compensated, in reverse item order; held items are cancelled once the parent starts compensating, they never ran and have nothing to compensate |
| parent ends without waiting | its held items are cancelled; admitted items run to their end |
| parent retried after its compensation | the `COMPENSATED` batch starts new items for the same keys, in the same order (other keys are an `IllegalStateException`) |
| an item cancelled from elsewhere | its outcome is `Cancelled`; the batch goes on |

The [history](../jobs/README.md#history) records `BATCH_STARTED` and `BATCH_ENDED` on the batch
step (with `batch.items`, `batch.parallelism`, `batch.failFast` and, after a fail-fast end,
`batch.failedFast`), `HELD` and `ADMITTED` on the items, caused by the parent. The
[admin UI](../operations/admin.md) shows the batch as one step with its progress and lists its
items.

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
  parent would publish); they show as `WAITING` jobs in `findJob` and the history, and a wait
  timeout bounds them.
- **Compatible `JsonMapper`s**: the child's node maps the result with its mapper, the parent's
  node reads it with its own; when parent and child types run in different services, the mappers
  must agree on the format.
- **History**: the child's `SCHEDULED` entry has the `JOB` actor, the parent's step records
  `CHILD_STARTED` (with `childJob.id`) and the parent `SUSPENDED`; the child's end records
  `CHILD_ENDED` (with `childJob.state`) on the step and `RESUMED` on the parent.
