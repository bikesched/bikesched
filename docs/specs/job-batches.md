# Specification: job batches (`job.newJobBatch`, `JobBatch.await`)

Status: **implemented**, [ADR 0066](../decisions/0066-child-job-batches-with-held-items.md) and
[ADR 0067](../decisions/0067-result-types-on-typed-jobs.md), user docs:
[Batches](../multi-step-jobs/child-jobs.md#batches) and
[Result types](../multi-step-jobs/child-jobs.md#result-types).

## 1. Goal

A job splits work into items, each with its own key and payload, runs them in parallel on any
node, optionally at most `k` at a time, and waits once for all of them. The code reads like the
intent:

```java
// a typed job with a result type (§4.2): payload Render, result Image
static final ResultJob<Render, Image> RENDER_FORMAT =
    TypedJob.of("render-format", Render.class, Image.class);

// returns the Image instead of calling job.complete(image)
bikesched.registerResultJobHandler(RENDER_FORMAT, (job, render) -> images.render(render));

bikesched.registerJobHandler("process-image", job -> {
    UUID imageId = job.payload(UUID.class);

    JobBatch<Image> renders = job.newJobBatch("render", RENDER_FORMAT)
        .addAll(Format.ALL, f -> new Render(imageId, f))   // payload type checked: Render
        .parallelism(4)                                    // optional: at most 4 at a time
        .schedule();                                       // one transaction, exactly once

    List<Image> images = renders.await().orElseThrow();    // typed by the batch, no class
    job.complete(new Renditions(images));
});
```

A batch of one job is typed by it: the payload of every item and the result of `await()`. Small,
fixed batches list items one by one, optionally with a key:

```java
JobBatch<ScanReport> scans = job.newJobBatch("scans", VIRUS_SCAN)
    .add("upload", new Scan(uploadId))         // with a key
    .add(new Scan(thumbnailId))                 // keyed by position
    .schedule();
```

A batch may also mix jobs. Then each item names its job, and each result is read typed by it:

```java
JobBatch<Object> checks = job.newJobBatch("checks")
    .add(VIRUS_SCAN, "virus", new Scan(fileId))
    .add(NSFW_CHECK, "nsfw", new Scan(fileId))
    .schedule();
JobResult<ScanReport> virus = checks.await().get("virus", VIRUS_SCAN);
```

The 500 mails of the motivating example are one item per mail with `parallelism(50)` (keys are
strings: `Recipient::id` returns a `String` here; a `UUID` id needs `r -> r.id().toString()`).
`SEND_MAIL` has no result type, so the batch is a `JobBatch<Void>`:

```java
job.newJobBatch("mails", SEND_MAIL)
    .addAll(recipients, Recipient::id, r -> new Mail(campaignId, r))
    .parallelism(50)
    .schedule()
    .await()
    .orElseThrow();
```

- Each item is an ordinary child job: any node handling the item's job name (or listing it as a
  [remote job name](../jobs/README.md#jobs-other-nodes-handle)) claims it, retries it on its own
  `maxAttempts` and records its own history.
- The batch, all its items and their steps are created in one transaction, exactly once (§4.4).
  The parent resumes once, when the last item has ended.

## 2. Today

Fan-out works with child jobs (`docs/patterns.md#fan-outfan-in`, example `fan-out`):

```java
List<UUID> ids = files.stream()
    .map(f -> job.newJob(RESIZE).payload(new Photo(f)).stepName("resize " + f).schedule())
    .toList();
for (UUID id : ids) { results.add(job.waitForJob(id, Thumbnail.class)); }
```

| Problem | Cause |
|---|---|
| N transactions to start N items | each `schedule()` is a step transaction of its own |
| up to N replays of the parent, each reading all N steps (O(N²)) | a child's end makes a `WAITING` parent due whatever it waits for (child-jobs spec, decision 6) |
| no limit on items running at once | all children are due right away; only each node's `maxConcurrentJobs` bounds them, not the batch |
| step names, loops and outcome handling written by hand | no API for a batch |

## 3. Scope

In scope:

- Starting N child jobs as one batch step, of one job or mixed, with a key and a payload per
  item.
- An optional `parallelism(k)` across all nodes, and an optional fail-fast mode (§4.12).
- The item's key in its own handler (`job.batchKey()`, §4.13).
- A node setting for the maximum batch size (`maxJobBatchSize`, default 1,000).
- Waiting for the whole batch, with an optional timeout, returning every item's `JobResult`.
- Kotlin extensions, the admin UI showing a batch, history entries.

Not in scope:

- Running items inside the parent's attempt, without job rows (§4.11).
- Adding items to a batch after it started, or batches of batches (an item can start its own batch).

## 4. Design

### 4.1 API

```java
public interface Job {
  // durable steps of this job, like job.step, job.sleep and job.newPoll

  // a batch of one job, typed by its payload and result (ResultJob, §4.2)
  <P, R> JobBatchBuilder<P, R> newJobBatch(String stepName, ResultJob<P, R> job);
  // a batch of one job without a result type: Void results
  <P> JobBatchBuilder<P, Void> newJobBatch(String stepName, TypedJob<P> job);
  // a batch of one job by its name: untyped payloads and results
  JobBatchBuilder<Object, Object> newJobBatch(String stepName, String jobName);
  // a batch mixing jobs: each item names its job
  MixedJobBatchBuilder newJobBatch(String stepName);
}

public interface JobBatchBuilder<P, R> extends JobBatchSettings<JobBatchBuilder<P, R>> {
  JobBatchBuilder<P, R> add(P payload);                       // keyed by position ("0", "1", …)
  JobBatchBuilder<P, R> add(String key, P payload);
  JobBatchBuilder<P, R> addAll(Iterable<? extends P> payloads);    // ready payloads, by position
  <T> JobBatchBuilder<P, R> addAll(Iterable<T> items, Function<? super T, ? extends P> payload);
  <T> JobBatchBuilder<P, R> addAll(Iterable<T> items,
      Function<? super T, String> key, Function<? super T, ? extends P> payload);
  JobBatchBuilder<P, R> parallelism(int items);                  // default: unbounded
  JobBatchBuilder<P, R> failFast();         // resume and cancel the rest on the first failure
  JobBatchBuilder<P, R> compensateWith(CompensationHandler<? super JobBatchItem> compensation);
  JobBatch<R> schedule();
  JobBatch<R> schedule(Instant at);
  JobBatch<R> schedule(Duration delay);
}

public interface MixedJobBatchBuilder extends JobBatchSettings<MixedJobBatchBuilder> {
  <P> MixedJobBatchBuilder add(TypedJob<P> job, P payload);
  <P> MixedJobBatchBuilder add(TypedJob<P> job, String key, P payload);
  <T, P> MixedJobBatchBuilder addAll(TypedJob<P> job, Iterable<T> items,
      Function<? super T, ? extends P> payload);                 // and with a key function
  // the same with a String jobName; parallelism, compensateWith as above
  JobBatch<Object> schedule();                                   // also at an Instant, a Duration
}

// priority, maxAttempts, timeout, reference: one value for every item
public interface JobBatchSettings<B> { … }   // also parallelism, failFast, compensateWith,
                                              // declared once for both builders

/** A started batch: the handle the job waits with. */
public interface JobBatch<R> {
  String stepName();
  Map<String, UUID> jobIds();                                    // key -> child id, in item order
  JobBatchResult<R> await();
  JobBatchResult<R> await(Duration timeout);
}

public interface JobBatchResult<R> {
  boolean isAllSucceeded();
  List<@Nullable R> orElseThrow();          // results in item order, or a JobFailedException
  Map<String, @Nullable R> succeeded();     // key -> result, in item order
  Map<String, JobResult<R>> unsucceeded();  // key -> Failed, Cancelled, TimedOut, …
  Map<String, JobResult<R>> all();          // every item, in item order
  JobResult<R> get(String key);                           // one item, typed by the batch
  <T> JobResult<T> get(String key, ResultJob<?, T> job);  // one item of a mixed batch, typed
  <T> JobResult<T> get(String key, Class<T> type);        // the same for string job names
}

/** What a batch compensation receives per started item. */
public record JobBatchItem(String key, UUID jobId) {}
```

Developer experience is the main design driver:

- **A step, and the signature says so**: the first argument is the step name, as for
  `job.step(name, …)`, `job.sleep(name, …)` and `job.newPoll(name, …)`, and the handle exposes
  `stepName()`. Like every step name it must be unique within the job and stable across deploys;
  the Javadoc of `newJobBatch` starts with "Starts a batch of child jobs as a durable step".
- **Typed from the start**: `newJobBatch(stepName, RENDER_FORMAT)` fixes the payload type of
  every `add` and the result type of `await()` at compile time: `JobBatch<Image>` needs no class
  anywhere. A job without a result type gives `JobBatch<Void>`; a job name as a string gives
  `JobBatch<Object>` with payloads mapped as for `newJob(String)`. Overload resolution picks the
  `ResultJob` variant, the most specific one, for a `ResultJob`.
- **Mixed batches are a separate builder type** (`newJobBatch(stepName)` without a job), as the API
  conventions ask for mutually exclusive options: each item names its job, the batch is a
  `JobBatch<Object>`, and `get(key, VIRUS_SCAN)` reads one result typed by its job.
- **Keys are optional**: the key names the item's step (`render/webp`), keys the result maps and
  shows in the admin UI and the history. `add(payload)` leaves it out and keys the item by its
  position. That is stable across replays, since the items are read only on the first run (§4.3);
  a meaningful key is still worth it where people read it (a format, a recipient id).
- **One fluent path ending in the verb** (`schedule()`), like every builder. `add` and `addAll`
  can be mixed and called repeatedly; items keep their order. The overloads differ in arity, so
  none is ambiguous, also for a `String` payload (`add(String key, P payload)` has two arguments).
- **Ready payloads need nothing else**: in a batch of one job, the job name and payload type come
  from `newJobBatch(stepName, job)`, so `addAll(renders)` adds a list of payloads as they are,
  keyed by position. Keyed payloads use the three-argument form with the identity:
  `addAll(mails, Mail::recipientId, mail -> mail)`. A two-argument `addAll(payloads, key)` is
  left out on purpose: it would have the arity of `addAll(items, payload)`, and a lambda could
  match both.
- **The handle, not a name, connects start and wait**: `schedule()` returns a `JobBatch`, and
  `renders.await()` reads as what it does. No step name to repeat, no list of ids to pass
  around. On a replay `schedule()` returns the same handle (§4.3), so the code needs no "first
  run" branch. `await` belongs to the job: it suspends it, and is rejected inside a step handler
  like `job.waitForJob`.
- **Outcomes for the common cases**: `orElseThrow()` for "all or nothing", `succeeded()` and
  `unsucceeded()` for "use what worked, report the rest", `all()` for full control with the
  sealed `JobResult` in a `switch`. A failed item never fails the parent by itself.
- **Nullness**: every type parameter is `extends @Nullable Object` (JSpecify), results are
  `@Nullable R` (a `JobBatch<Void>` returns `null`s), and the result lists and maps allow `null`
  values (no `List.copyOf`). `get(key)` with a key that is not in the batch throws an
  `IllegalArgumentException` (it is not a `find…` lookup). In a mixed batch, `get(key)` maps an
  item of a `ResultJob` with its `resultType()`, other items as `Object`.
- **Mistakes fail fast, before anything is written**: duplicate keys (all listed), an invalid
  key (1 to 200 characters, like step names), `parallelism` below 1, a step name already used by
  another step type, or `schedule()` inside a step handler throw an `IllegalArgumentException` or
  `IllegalStateException` with the step name.
- An empty batch is allowed: it is stored with no items, and `await` returns an empty result
  right away (handlers need no special case for "nothing to do").
- What is left out on purpose: no `stepName` per item (the key names the item), no
  `idempotencyKey` (the batch creates each item exactly once, and idempotency keys are unique per
  job name across all parents), no `connection(…)` (the batch's transaction is the parent's,
  §4.4).

### 4.2 Result types on typed jobs

Today a `TypedJob<P>` names a job and its payload type; the result type is passed to every wait
(`waitForJob(id, Receipt.class)`, child-jobs spec decision 8). Batches make that repetitive and
error-prone (a wrong class only fails when the result is mapped), so this spec revisits the
decision:

```java
public sealed interface TypedJob<P extends @Nullable Object> permits PayloadJob, ResultJob {
  String name();
  Class<P> payloadType();

  static <P extends @Nullable Object> TypedJob<P> of(String name, Class<P> payloadType);
  static <P extends @Nullable Object, R extends @Nullable Object> ResultJob<P, R> of(
      String name, Class<P> payloadType, Class<R> resultType);
}

public record ResultJob<P extends @Nullable Object, R extends @Nullable Object>(
    String name, Class<P> payloadType, Class<R> resultType) implements TypedJob<P> {
  // validates the name like TypedJob today
}

// a handler that returns the result instead of calling job.complete(result); its own name, as
// registerJobHandler(TypedJob, TypedJobHandler) also accepts a ResultJob and an implicitly typed
// lambda like (job, render) -> images.render(render) would match both
<P extends @Nullable Object, R extends @Nullable Object> Bikesched registerResultJobHandler(
    ResultJob<P, R> job, ResultJobHandler<? super P, ? extends R> handler);

@FunctionalInterface
public interface ResultJobHandler<P extends @Nullable Object, R extends @Nullable Object> {
  R handle(Job job, @Nullable P payload) throws Exception;
}
```

- **Optional**: jobs without a result stay `TypedJob<P>` from `TypedJob.of(name, payloadType)`,
  declared, registered and awaited exactly as today; nothing changes for them. Only a job that
  returns something is declared with a third argument. A batch of such a job is a
  `JobBatch<Void>`, awaited with `await()` (every result `null`), as in the mail example.
- `TypedJob` becomes a sealed interface with two records: `PayloadJob<P>` (today's record, the
  type `TypedJob.of(name, payloadType)` returns) and `ResultJob<P, R>`. Every API taking a
  `TypedJob` accepts both unchanged at the source level; `new TypedJob<>(…)` and record patterns
  break (two Kotlin constructor calls in `bikesched-examples/kotlin`, `shop/JobHandlers.kt`). It
  is a binary break: code compiled against the record fails with `IncompatibleClassChangeError`
  until recompiled. Fine at 0.x with a `!` commit; the ADR and the release notes say so.
- A `ResultJob` held as a `TypedJob` (a field of that type, `StandaloneJobHandler.typedJob()`)
  selects the `TypedJob` overloads, which ignore the result type: a batch of it is a
  `JobBatch<Void>` with `null` results, never `Image`s typed as `Void`.
- A `ResultJobHandler` returns the result; bikesched stores it like `job.complete(result)` (the
  same precedence: cancel and fail still win). `null` is a result without a value.
- Waits typed by the job, next to the class-based ones: `job.waitForJob(id, CHARGE)` for single
  children; a batch of a `ResultJob` is typed by it (§4.1). The result is mapped with the job's
  `resultType()`.
- Mismatches fail early: `get(key, job)` for an item of another job throws an
  `IllegalArgumentException` naming both job names.
- Kotlin already reifies result types (`awaitJob<T>`); it gets
  `handleResultJobs<P, R>(name) { payload -> result }`, a name of its own: a `handleJobs<P, R>`
  would erase to the signature of `handleJobs<T>` and silently drop a result returned by a
  lambda written for it.
- Not in scope: results for `StandaloneJobHandler`, `@JobHandler` methods (`JobHandlers` rejects
  non-void methods) and the Spring starter's discovery; a follow-up, as for `job.complete` today.
- It is implemented together with batches (decision 6), for single child jobs too, with its
  own ADR ("Result types on typed jobs", superseding the result-class part of ADR 0029's
  design).

### 4.3 Steps

A batch is one step of the parent, of the new type `CHILD_JOB_BATCH`, named `stepName`, plus one
`CHILD_JOB` step per item, named `stepName + "/" + key` and pointing to the batch step with the
new column `batch_step_id`. The item steps are ordinary child job steps: they reference their child,
receive its outcome and can be compensated.

- The batch step is the parent's view of the batch: `WAITING` while items run, `SUCCEEDED` once all
  ended, `TIMED_OUT` or `CANCELLED`. It stores the size and the parallelism, so later runs and the
  admin UI need no other source.
- The items are read only on the first run. Later runs find the batch step and return the stored
  handle, whatever the items are now: like the deadline of a sleep, the stored batch wins over a
  changed argument. A step name used by another step type is rejected, like for every step.
- `schedule()` and `await` are rejected inside a step handler, like any nested step.

### 4.4 Transactions

Creating a batch is all or nothing. `schedule()` validates everything first (keys, parallelism,
the step guard, payload mapping by the `JsonMapper`), then writes in **one transaction**
(`access.inTransaction`), fenced by the parent's lock:

1. `StepStore.requireJobLock`: the parent still runs on this node with this lock token; otherwise
   `JobLockLostException` and nothing is written.
2. Look up the batch step by name (`for update`). If it exists, commit nothing new and return
   the stored handle (a replay). A `COMPENSATED` batch step starts a new batch with new children.
3. Insert the batch step (`CHILD_JOB_BATCH`, `WAITING`, `item_count`, `parallelism`).
4. Insert all items with one `unnest` insert per chunk of 1,000 items: the first `parallelism`
   items (all without a limit) `SCHEDULED`, the rest `HELD`; each with `batch_step_id`, its
   `batch_index` (0, 1, …) and `batch_key`.
5. Insert the item steps (`CHILD_JOB`, `WAITING`, `batch_step_id`) the same way, in item order;
   steps of held items with `compensable = false` (set on admission, §4.5). Step ids are
   time-ordered and monotonic within one JVM (ADR 0057), so the id order is the item order.
6. Record the history (`BATCH_STARTED` on the batch step; `SCHEDULED` or `HELD` per child, caused
   by it).
7. Commit. Only then are other nodes notified of the scheduled items (`db.due(name, time)` per job
   name, sent after the commit like every due write).

What this guarantees:

- A crash, a lost lock or a failing statement at any point leaves no trace: no batch step, no
  items, no orphaned children running for a parent that does not know them. The next attempt
  creates the batch from scratch.
- A committed batch is complete: every item exists with its step, and no item can end before
  the batch step exists (an item is only claimable after the commit).
- Two attempts of the same parent (a node that lost its job while another claimed it) cannot both
  create the batch: the lock fence rejects the stale one, and the unique `(job_id, name)` of the
  steps rejects a duplicate in any case.

The other writes of a batch are transactions of their own, each consistent on its own:

| Write | One transaction containing |
|---|---|
| an item ends | the item's terminal update, the batch step's counters, its item step, the admission of held items, the batch step's completion and the parent's resume (§4.6) |
| the batch times out | the batch step and the unfinished item steps `TIMED_OUT`, the held items `CANCELLED`, in the parent's attempt (§4.7) |
| the parent is cancelled | the batch step and waiting item steps `CANCELLED` and the held items `CANCELLED`, with the parent's cancellation |
| the parent ends otherwise with a batch still `WAITING` | its held items `CANCELLED`, in the parent's terminal transaction (§4.9) |
| a held item is cancelled from elsewhere | `HELD` to `CANCELLED` directly, its step and the batch's counters updated, the batch checked for completion; no admission (§4.5) |
| fail-fast: the first item that did not succeed | the batch step `FAILED`, the parent resumed; the siblings cancelled after the commit (§4.12) |

Every one of them takes its locks in the order of §4.14.

Large batches make for a large first transaction: 1,000 items are 2,000 rows plus their history
rows and about four index entries per job, while the parent's row is held `for share` (blocking a
cancel request of the parent and the ends of its other children meanwhile). The size limit
(`maxJobBatchSize`, default 1,000, decision 9) keeps that short; a node may raise it, and then the
parent's attempt timeout and the hard timeout must allow for the insert. The stress test measures
the insert at 1,000 and 10,000 items under load, for the documentation of the setting.

### 4.5 Parallelism and the `HELD` state

`parallelism(k)` limits the active items of the batch (scheduled, running, waiting, retrying:
admitted and not ended) to `k`. A held item is a job in the new state `HELD`: stored and visible
(`findJob`, the admin UI) but never claimed, because the claim query only takes `SCHEDULED`,
`WAITING`, `COMPENSATING` and `FAILING` jobs.

The batch step keeps two counters, updated under its lock: `active_count` (admitted, not ended)
and `ended_count`. When an item ends (§4.6), its transaction admits `k - active_count` held items,
the first ones in item order (`batch_index`), with `for update skip locked` (a held item being
cancelled at that moment is skipped, not waited for). They become `SCHEDULED` and due now
(`db.dueNow(name)`), their steps compensable, recording `ADMITTED` (`RELEASED` already names jobs
released from a dead node). Computing the number from the counter, instead of releasing one per
end, keeps the limit exact: a held item cancelled from elsewhere ends without admitting anything,
and a skipped row is admitted by the next end.

The claim query is unchanged: the limit costs one indexed statement per item end, not a filter on
every claim (§4.11).

### 4.6 An item ends

The end hook of child jobs (`COMPLETE_CHILD_JOB_STEP_SQL`, then `RESUME_PARENT_SQL`) gets a batch
branch when the ending job has a `batch_step_id`. After the item's own terminal update, in this
order (§4.14):

1. Lock the batch step (`select … for update`), before touching the item step. This serializes
   the ends of one batch: without it, two items ending at the same time could each see the other
   unfinished, and nobody would resume the parent.
2. Complete the item step, as today (only if it is still `WAITING`: a timed-out, cancelled or
   failed-fast batch leaves its item steps as they are).
3. Increment `ended_count`, decrement `active_count`.
4. If the batch step is still `WAITING`: admit held items (§4.5); in a fail-fast batch, an item
   that did not succeed ends the batch (§4.12).
5. If the batch step is still `WAITING` and `ended_count = item_count`, complete it
   (`update … set state = 'SUCCEEDED' … where state = 'WAITING'`) and resume the parent with
   `RESUME_PARENT_SQL`.

Counters instead of a count over the item steps keep every end O(1) (a 1,000 item batch would
otherwise scan its steps 1,000 times, under the lock). An item ending while the parent waits for
something else no longer makes it due, except the batch's last item: `RESUME_PARENT_SQL` makes any
`WAITING` parent due, so a parent sleeping while its batch completes replays once, finds its sleep
unfinished and suspends again, as for single children today.

### 4.7 Waiting

- If the batch step is `SUCCEEDED`, `FAILED` (fail-fast), `TIMED_OUT` or `CANCELLED`, the wait
  returns right away, on every run: one query reads the batch step and all item steps
  (`where batch_step_id = ? order by id`).
- Otherwise the attempt ends there (`JobSuspended`, a new `Suspension` for batches) and the parent
  is `WAITING`, without a thread or slot. The suspension locks the batch step before
  `SUSPEND_JOB_SQL` and re-checks it, as `suspendAtChildJob` locks a child step today: a last item
  ending meanwhile either finds the parent `WAITING` and resumes it, or has completed the batch
  before the lock, and the parent does not suspend. With a timeout, the first wait stores the
  deadline in the batch step's `wake_at`; the stored one wins over a changed argument.
- At the deadline, the parent's wake-up (`DueTimes`) runs it again and the wait, locking the batch
  step first, marks it and every unfinished item step `TIMED_OUT`, then cancels its held items
  (they never ran, nothing to undo; message `batch 'render' timed out`). Running items keep
  running; their ends no longer change their steps or the batch, so every later replay returns the
  same `JobResult.TimedOut` for them.

### 4.8 Outcomes

`JobBatchResult` wraps the items' `JobResult`s; no new outcome records. A failed item never fails
the parent by itself: the parent decides, with `orElseThrow()` (a `JobFailedException` whose
message names the keys of the items that did not succeed, the outcomes attached),
`isAllSucceeded()`, `get(key)` or a loop over `all()`.

Results are mapped from JSON lazily, per item, when they are read (`get`, `succeeded()`,
`orElseThrow()`, …), with the node's `JsonMapper` and the job's `resultType()`, as `waitForJob`
maps a child's result. `get("webp")` maps one item only; a result that cannot be mapped throws
for that item without affecting the others. With the default mapper (`RawJsonMapper`),
`get(key, String.class)` returns the stored JSON text.

`get(key)` reads one item of an ended batch. Waiting for a single item before the batch has
ended is not offered: `job.waitForJob(id, …)` on an item of a batch is rejected
(`IllegalArgumentException`, "wait for the batch"), because an item's end only resumes the parent
once the whole batch has ended (§4.6). See decision 5.

### 4.9 Cancellation, compensation and retries

| Situation | Behaviour |
|---|---|
| parent cancelled while the batch runs | batch step and the waiting item steps `CANCELLED`; held items cancelled; running items not touched, unless the batch's compensation cancels them |
| `compensateWith((step, item) -> …)` | each admitted item step is compensable, as a child step; compensations run in the reverse order of the items' steps; steps of held items are not compensable (`compensable = false` until admitted), so cancelled held items have nothing to compensate |
| an item cancelled from elsewhere | its result is `Cancelled`; the batch goes on and admits the next held item |
| a held item cancelled from elsewhere | allowed (it never ran): `Bikesched.cancel` turns `HELD` into `CANCELLED` directly (`REQUEST_CANCEL_SQL` only matches claimable states); it counts as ended and admits nothing |
| parent retried after a failure | the batch step returns the same children; a `COMPENSATED` batch restarts: like `RESTART_CHILD_JOB_STEP_SQL`, its item steps are reset and get new children, with the new payloads; the keys must be the same as before (`IllegalStateException` naming the differences) |
| `bikesched.retry(itemId)` | rejected, as for every child |
| parent ends without waiting | allowed; the parent's terminal transaction cancels the held items of its batches still `WAITING`, as nobody would admit them once the parent is deleted; admitted items run to their end |
| parent deleted after its retention | the cascade removes the batch and item steps; the items keep `parent_job_id`, and none is left `HELD` (previous row) |
| an item fails permanently | its retries are its own; the batch records the outcome, the parent decides; in a fail-fast batch the batch ends (§4.12) |

### 4.10 Storage

Into `V001` in place (before 0.1.0):

- `bikesched_jobs.state`: add `HELD` to the CHECK, only for `type = 'CHILD'` with a
  `batch_step_id`; a held job has no lock, no attempts used and a `scheduled_at` of its insert
  (admission sets it to `now()`). `HELD` is not an end state, so the retention clean-up never sees
  it. The existing partial indexes (claim, recurring) and `NEXT_DUE_SQL` exclude it already.
- New columns on `bikesched_jobs`: `batch_step_id uuid` and `batch_index integer` (both or
  neither, only with a `parent_job_id`) and `batch_key text collate "C"` (§4.13, the name CHECK,
  only with a `batch_step_id`). Index `bikesched_jobs_held_idx on (batch_step_id, batch_index) where
  state = 'HELD'` for admissions and for cancelling held items. (`bikesched_jobs` has no index on
  `parent_job_id`; this one avoids needing it.)
- `bikesched_steps.type`: add `CHILD_JOB_BATCH`; it waits and times out: add it to the `TIMED_OUT`
  list of `bikesched_steps_event_check` and to `bikesched_steps_wait_check`; no `child_job_id`, no
  result.
- New columns on `bikesched_steps`:
  - `batch_step_id uuid`: the batch of an item step; only on `CHILD_JOB` steps (CHECK), no FK
    (rows of one job are deleted together by the cascade).
  - `item_count integer` and `parallelism integer`: only on `CHILD_JOB_BATCH` steps, `parallelism`
    null for unbounded, both positive (CHECK).
  - `active_count integer` and `ended_count integer`: the counters of §4.5 and §4.6, only on
    `CHILD_JOB_BATCH` steps, `0 <= ended_count <= item_count` (CHECK); their updates are HOT
    (no indexed column changes). The admin UI's "k of N ended" reads them.
  - `fail_fast boolean not null default false`: true only on `CHILD_JOB_BATCH` steps (CHECK); a
    batch step is `FAILED` only if `fail_fast` (`state <> 'FAILED' or fail_fast`).
- History CHECKs (`bikesched_history`): `HELD` in the job state list of
  `bikesched_history_action_check`; the job action `ADMITTED`; the step actions `BATCH_STARTED` and
  `BATCH_ENDED`; all three in the CHECK test of `HistoryStoreTest`.
- Index `bikesched_steps_batch_step_id_idx on (batch_step_id, id) where batch_step_id is not null`
  for the end hook and the wait.
- `docs/operations/database.md`: the new state, columns and index.
- `DueJobsWritePathsTest`: classify the batch insert (`due`) and the admission (`dueNow`).

### 4.11 Rejected alternatives

- **Parallel steps inside the parent's attempt** (`job.forEachStep(…).parallelism(50)`): no job
  rows, but all items run on the one node that claimed the parent, share its attempt timeout and
  slot, cannot retry on their own and break "one step at a time" (`StepGuard`, compensation order,
  postponing). It does not meet the goal of spreading load over nodes. Short I/O can already run in
  parallel inside one step.
- **`waitForJobs(ids)` over arbitrary children**: needs the same membership to resume once, but
  without a name the wait cannot be found on replay and the admin UI has nothing to show.
- **Parallelism as a filter in the claim query** (skip a batch's items while `k` run): every claim
  of every job pays for a subquery, and held items look due although they are not.
- **Held items as `SCHEDULED` with `scheduled_at = 'infinity'`**: no new state, but the admin UI and
  `findJob` show jobs scheduled for never, and nothing tells them apart from user-scheduled ones.
- **Storing the step a job is suspended at** (`bikesched_jobs.suspended_step_id`): removes all
  spurious wake-ups, not only for batches, but gives no batch wait; it stays possible later.
- **Items publishing "done" events the parent waits for**: outcomes and failures are already carried
  by `JobResult`; events add a key per item and lose the child's error.

### 4.12 Fail-fast

```java
var renders = job.newJobBatch("render", RENDER_FORMAT)
    .addAll(Format.ALL, f -> new Render(imageId, f))
    .failFast()
    .schedule();
List<Image> images = renders.await().orElseThrow();   // throws for the first failed item
```

By default a batch ends when every item has ended, so a parent learns about a failed item only
after the slowest one. `failFast()` ends the batch at the first item that does not succeed
(`Failed`, `Cancelled`, `CompensationFailed`). The mode is stored on the batch step
(`fail_fast`) when the batch is created, because the item's end hook has to know it.

In that item's end transaction, after locking the batch step (§4.6):

1. The batch step becomes `FAILED`, with the failed item's key in `last_error`
   (`item 'webp' failed: …`).
2. The other item steps still `WAITING` become `CANCELLED` (steps only, in id order, all behind the
   batch step's lock in §4.14), so their later ends no longer change them, as for a cancelled
   parent.
3. The parent is resumed.
4. After the commit, a follow-up transaction of the same node cancels the siblings, in id order
   and `for update skip locked` (a sibling ending at that moment is left to end on its own): held
   items become `CANCELLED` (they never ran), scheduled and running items get a cancel request, as
   `bikesched.cancel(id, "batch 'render' failed fast")` would; a running handler sees it at its
   next step, compensations of the item run as usual. The history records them caused by the
   batch step. Locking sibling job rows while holding the batch step would deadlock with a
   sibling ending at the same time (it holds its own row and waits for the batch step).

`await()` then returns the items as they are: the failed item with its outcome, items that
succeeded before with their results, all others `Cancelled` with the batch's message. A
`Cancelled` sibling may still have finished its work (the cancel request is cooperative, and an
item may succeed before it sees it); the parent's view stays `Cancelled` on every replay.
`orElseThrow()` names the first failed key. Two items failing at the same time are serialized by
the lock on the batch step: the second finds the batch no longer `WAITING` and only completes its
own step. A batch that is not fail-fast is unchanged. The history's `BATCH_ENDED` records
`failedFast` with the key.

### 4.13 The item's key in its handler

```java
bikesched.registerResultJobHandler(RENDER_FORMAT, (job, render) -> {
    log.info("rendering {}", job.batchKey());   // "webp"; null for jobs outside a batch
    return images.render(render);
});
```

- The batch insert (§4.4) stores each item's key in `bikesched_jobs.batch_key`, positional keys
  (`"0"`, `"1"`, …) included.
- `Job.batchKey()` and `JobInfo.batchKey()` return it, `@Nullable` (no batch). The MDC of the job's
  thread gets `bikesched.batchKey` (`BikeschedLogContext.BATCH_KEY`) when it is set; the admin UI
  shows it on the job and in the batch's item list.
- Kotlin reads it as `batchKey()` on the `Job` receiver; no extension needed.

### 4.14 Lock order

Every transaction touching a batch takes its locks in one order, so no two of them wait for each
other in a cycle:

1. the ending item's own job row (its terminal update or claim end), if any;
2. the batch step;
3. item steps, in id order;
4. other item job rows (admissions, cancelling held items), in `batch_index` order and only with
   `for update skip locked`, never waiting;
5. the parent's job row (`RESUME_PARENT_SQL`, or the fence `requireJobLock`, `for share`).

Consequences, each stated where it applies: the end hook locks the batch step before its item
step (§4.6); the parent's transactions (timeout, suspension, cancellation, its terminal
transaction) lock the batch step before the fence, as `suspendAtChildJob` locks the step before
the job today, and the parent's cancellation locks its batch steps explicitly (`for update`, in id
order) before `CANCEL_STEPS_SQL` updates them; fail-fast cancels siblings after its commit
(§4.12). The store tests race each pair: two items ending, an item ending against the timeout,
the suspension, the parent's cancellation, a held item's cancellation and a fail-fast end.

## 5. Kotlin

A builder lambda instead of a fluent chain, so loops and conditions read naturally. The batch is
typed at the start with reified type arguments, so `await()` (the Java member) is typed too:

```kotlin
val renders = startJobBatch<Render, Image>("render", "render-format", parallelism = 4) {
    for (format in Format.ALL) item(Render(imageId, format), key = format.name)
}
val images: List<Image> = renders.await().orElseThrow()

startJobBatch<Mail, Unit>("mails", "send-mail", parallelism = 50) {
    recipients.forEach { item(Mail(campaignId, it), key = it.id) }
}.await()

val checks = startJobBatch("checks") {               // mixed: each item names its job
    item("virus-scan", Scan(fileId), key = "virus")
    item("nsfw-check", Scan(fileId), key = "nsfw")
}
val virus: JobResult<ScanReport> = checks.await().result<ScanReport>("virus")
```

- `Job.startJobBatch<P, R>(stepName, jobName, parallelism, failFast, priority, maxAttempts,
  timeout, reference, at, delay, compensation) { item(payload, key) }`: named arguments like
  `startJob`, the trailing lambda (a plain function, not suspend: it only lists items, before the
  transaction) collects the items and returns the `JobBatch<R>` from Java. `key` is optional and
  defaults to the item's position, like `add(payload)` in Java. `R = Unit` maps to `Void`, like
  `Unit` steps.
- `Job.startJobBatch(stepName, …) { item(jobName, payload, key) }` without type arguments is the
  mixed batch; `JobBatchResult.result<T>(key)` reads one item reified.

Items can also be passed as a list instead of the lambda, for code that maps a collection
immutably. It is the same function with an `items` parameter, not a second style of extension:

```kotlin
val renders = startJobBatch<Render, Image>(
    "render", "render-format", parallelism = 4,
    items = Format.ALL.map { BatchItem(Render(imageId, it), key = it.name) },
)

startJobBatch<Mail, Unit>(
    "mails", "send-mail", items = recipients.map { BatchItem(Mail(campaignId, it)) },
)

val checks = startJobBatch(
    "checks",
    items = listOf(
        MixedBatchItem("virus-scan", Scan(fileId), key = "virus"),
        MixedBatchItem("nsfw-check", Scan(fileId), key = "nsfw"),
    ),
)
```

- `data class BatchItem<out P>(val payload: P, val key: String? = null)` for a batch of one job,
  `data class MixedBatchItem(val jobName: String, val payload: Any?, val key: String? = null)` for
  a mixed one; `key = null` means the item's position, as with `item(…)`.
- Two shorter forms for a batch of one job, each its own named parameter (a `List<Pair>` and a
  `List<P>` overload would clash on the JVM after erasure, and `P` could itself be a `Pair`):

  ```kotlin
  // key to payload, as in mapOf("webp" to …); also map.toList()
  startJobBatch<Render, Image>("render", "render-format",
      pairs = Format.ALL.map { it.name to Render(imageId, it) })

  // payloads only, keyed by position
  startJobBatch<Render, Image>("render", "render-format",
      payloads = Format.ALL.map { Render(imageId, it) })
  ```

- `pairs` is named after the stdlib's `mapOf(vararg pairs: Pair<K, V>)` (Ktor's `headersOf` and
  `parametersOf` use the same name).
- `items`, `pairs`, `payloads` and the lambda can be combined, in that order; a batch with none
  is empty. The lambda becomes optional (`block: (…) -> Unit = {}`). Mixed batches take only
  `items` (`MixedBatchItem`) or the lambda, since each item needs its job name.
- The `kotlin.md` tables get the rows.

## 6. Documentation

- `docs/multi-step-jobs/child-jobs.md`: a section "Batches" (starting, keys, parallelism, waiting,
  outcomes, the cancellation table).
- `docs/patterns.md`: rewrite "Fan-out/fan-in" with a batch; the `fan-out` example uses it.
- `docs/jobs/README.md`: `HELD` in the job state table.
- `docs/jobs/changing-handlers.md`: the items are read once; changing them in a deploy does not
  change a started batch.
- `docs/integrations/kotlin.md`, `docs/operations/database.md`, `docs/operations/admin.md`.
- `docs/getting-started/configuration.md`: `maxJobBatchSize` in the builder option table, the
  `bikesched.batchKey` MDC key.
- ADR: "Child job batches with held items" (batch step, `HELD` state, resume once).

## 7. Admin UI

- Job detail: the batch step shows `k of N ended`, the parallelism and a link to its items (the
  job list filtered by parent and batch).
- Jobs list: `HELD` as a state filter; a held job can be cancelled, not retried.
- No new actions.

## 8. Testing

Core (`AbstractBikeschedTest`, per-test job names):

1. A batch of 10 items succeeds; results in item order; `succeeded()`, `unsucceeded()`; the
   parent ran exactly twice (start and resume), counted from the history.
2. Items run on two nodes: both nodes claimed items (`locked_by_node_id` in the history).
3. `parallelism(2)` with 6 items: never more than 2 items `RUNNING` or due (sampled), all released
   in item order, `RELEASED` recorded 4 times.
4. Two items ending in the same instant (a latch in both handlers): the parent resumes exactly once.
5. A failing item (`maxAttempts(2)`): `Failed` for its key, others `Succeeded`; `orElseThrow()`
   names the key.
6. Timeout: the batch times out, held items are `CANCELLED`, running ones keep running and their
   end does not resume the parent.
7. Parent cancelled: held items cancelled, running untouched; with `compensateWith`, every started
   item compensated, in reverse order.
8. Replay with changed items returns the stored handle; duplicate keys are rejected before
   anything is written; an empty batch returns right away.
9. A parent sleeping while items end is not woken by them, except one replay at the batch's last
   item, which finds the sleep unfinished and suspends again.
10. A compensated batch starts new children on the parent's retry.
11. A batch mixing three job names: each item runs its own handler, `get(key, type)` maps each
    result to its own type, `add` rejects a `null` key.
12. Fail-fast: the first failing item resumes the parent once; held items `CANCELLED`, running
    ones receive a cancel request after the commit; two items failing at once end the batch once,
    without a deadlock; a batch without `failFast()` waits for all.
15. Parallelism stays exact: with `parallelism(2)`, cancelling a held item and a running one at
    the same time never lets more than two items be active (sampled from the batch's counters and
    the jobs' states).
16. Timeout: a running item ending after the batch timed out leaves its step `TIMED_OUT` and the
    batch `TIMED_OUT`, on every replay; the parent is not resumed.
17. A parent ending without waiting cancels its held items; nothing stays `HELD` after the
    parent's retention.
18. A `COMPENSATED` batch restarts with the same keys; different keys are rejected.
13. `job.batchKey()` in an item's handler (keyed and positional), `null` outside batches, in the
    MDC (`LogCapture`) and in `JobInfo`.
14. `maxJobBatchSize`: a batch above the limit (1,001 items by default) is rejected before
    anything is written; with the limit raised, 2,500 items are inserted in three chunks.

Store level (`AbstractStoreTest`): the batch insert in chunks of 1,000 (2,500 items), the CHECKs
of the new columns and state, the end hook with and without `batch_step_id`, the history actions.
Kotlin: start and await, reified results. Stress tests: a batch of 5,000 items (the limit
raised) with `parallelism(50)` across three nodes, with a database restart during the run.

## 9. Open questions

None: all are decided (§11).

## 10. Implementation steps

1. Schema: state, type, columns, index, CHECK tests, `SchemaMigratorTest` unchanged (no new table).
2. `JobStore`/`StepStore`: batch insert, the end-hook branch with release and resume-once, the
   batch read; history actions.
3. `DefaultJob`: `newJobBatch`, `DefaultJobBatchBuilder`, `DefaultJobBatch.await`, the batch
   `Suspension`, compensation of item steps; `JobBatchResult` in `org.bikesched.job`.
4. Cancellation and timeout paths cancel held items; the retention guard.
5. Kotlin extensions; admin UI; docs, example and ADR.
6. Stress test.

## 11. Decisions

1. `HELD` is a job state of its own (former question 4).
2. The history records `BATCH_STARTED` and `BATCH_ENDED` on the batch step, not `CHILD_STARTED`
   per item; children keep their own entries (former question 5).
3. A batch of a job given by its name (`newJobBatch(step, "render-format")`) has `Object`
   payloads and results, like `newJob(String)`: payloads are mapped by the node's `JsonMapper`
   (JSON text with the default mapper), results read with `get(key, Class)` (former question 6).
4. Batches started by the application outside a job are not in scope; recorded in `IDEAS.md`
   (former question 7).
5. Waiting for single items before the batch ended is not in scope; `waitForJob` on a batch item
   is rejected (former question 8).
6. Result types on typed jobs (§4.2) are part of the same implementation, also for single child
   jobs (`waitForJob(id, CHARGE)`), with an ADR of their own.
7. The Kotlin API as in §5.
8. Fail-fast is supported as `failFast()` on the builder, stored with the batch (§4.12).
9. `maxJobBatchSize(int)` is a node setting, default 1,000 (a short creation transaction); items
   are inserted with `unnest` in chunks of 1,000 in the batch's transaction, for raised limits.
10. The item's key is stored (`batch_key`) and available as `Job.batchKey()`, in `JobInfo`, the
    MDC and the admin UI (§4.13).

## 12. Decided while implementing

1. Fail-fast cancels the siblings in the failing item's end transaction, not in a follow-up after
   the commit (§4.12): held items `CANCELLED`, scheduled, waiting and running ones a cancel
   request, all `for update skip locked` in `batch_index` order, after the batch step. The lock
   order of §4.14 holds (other item rows only with `skip locked`), so there is no deadlock, and no
   second transaction can be lost in a crash. A sibling locked at that moment (ending or being
   claimed) is skipped and ends on its own; its step is already `CANCELLED`.
2. History entries of items (`SCHEDULED`, `HELD`, `ADMITTED`, cancellations) are caused by the
   ending item or, at the start, by the parent job (`causedBy` names a subject, and a batch step
   is none).
3. An admitted item keeps a later due time: admission sets `scheduled_at =
   greatest(scheduled_at, now())`, so `schedule(Instant)` and `schedule(Duration)` delay held items
   too.
4. The parent's claim reads whether it has a `WAITING` batch (`exists` over the new partial index
   `bikesched_steps_waiting_batch_idx`); its terminal transactions lock those batch steps first
   and cancel their held items once the job ended. Jobs without batches take the unchanged path.
   `Bikesched.cancel` looks the batches up itself.
5. An empty batch is stored `SUCCEEDED` at once, with no items; `await()` returns an empty result.
6. A `COMPENSATED` batch restarts only with the same keys in the same order; the message names the
   missing and the new keys, or says the order changed.
7. The batch step's `compensable` means "its items have a compensation": the compensation replay
   registers a no-op for the batch step first (so it runs last) and the items' compensations in
   item order (so they run in reverse). Steps of held items stay not compensable until admitted.
8. The admin lists a batch as its batch step only (`findSteps` leaves out item steps), with a
   `StepBatch` (`items`, `ended`, `active`, `parallelism`, `failFast`) on `StepInfo.batch()`;
   `JobQuery.withBatch(stepId)` lists its current items (the children of its item steps, along
   `bikesched_steps_batch_step_id_idx`); `JobSummary.batchKey()` names an item.
9. Kotlin: the lambda's receivers are `BatchItems<P>` and `MixedBatchItems`; a mixed batch is a
   `JobBatch<Any>` (the Java `JobBatch<Object>`); `result<T>(key)` maps `Unit` to `Void`.
   `handleResultJobs` stores the lambda's value with `job.complete`, `Unit` as no value.
10. The `fan-out` example uses a batch with `parallelism(2)`; the stress test
    `JobBatchStressTest` runs 5,000 items on three nodes with a database restart.

