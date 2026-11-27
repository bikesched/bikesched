---
title: "Kotlin"
weight: 2
---

# Kotlin

The `bikesched-kotlin` module adds Kotlin extensions on top of the Java API, in the package
`org.bikesched.kotlin`; it depends on `bikesched-core`, the Kotlin standard library and
`kotlinx-coroutines-core`. Every handler lambda is a suspend lambda, so handlers call suspending
functions directly, see [Suspending functions](#suspending-functions).

```kotlin
// bikesched-jackson with Jackson's Kotlin module (tools.jackson.module:jackson-module-kotlin)
val kotlinObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

val bikesched = bikesched(ds) {                     // instead of Bikesched.builder(ds)…build()
    jsonMapper = JacksonJsonMapper(kotlinObjectMapper)
    defaultJobTimeout = 5.minutes                   // kotlin.time.Duration everywhere
}

data class Order(val id: String, val amount: Int)

bikesched.handleJobs<Order>("order") { order ->    // this: Job, order: the mapped payload
    val reservation = step<Reservation>("reserve-payment", compensation = { _, r -> payments.release(r) }) {
        payments.reserve(order)
    }
    val payment = waitForEvent<Payment>(step = "await-payment", name = "payment-received", key = order.id)
    val shipment = step<Shipment>("ship") { shipping.ship(order, reservation, payment) }
    publishEvent(name = "order-shipped", key = order.id, payload = shipment)
}

bikesched.createJob(name = "order", payload = Order("o-1", 100))
bikesched.publishEvent(name = "payment-received", key = "o-1", payload = payment)
bikesched.createRecurringJob(name = "cleanup", recurrence = every(1.hours)) { cleanup.run() }
```

## Functions

`createJob`, `createRecurringJob`, `publishEvent`, `waitForEvent` and `startJob` take named
arguments. Only the job name (resp. step, event name and key) and the recurrence are required;
omitted options keep the builder defaults:

| Function | Arguments |
|---|---|
| `handleJobs<T>(name) { payload -> }`, `handleJobs(name) { }` | registers the handler; the `Job` is the receiver |
| `handleResultJobs<P, R>(name) { payload -> result }` | registers a handler whose value is the job's [result](../multi-step-jobs/child-jobs.md#result-types), as `job.complete(result)` would store it; a name of its own, as a `handleJobs<P, R>` would erase to the signature of `handleJobs<T>` |
| `handleJobFailures<T>(name) { payload -> }`, `handleJobFailures(name) { }` | registers the [failure handler](../jobs/README.md#failure-handlers); the `FailedJob` is the receiver |
| `createJob(name, payload, at, delay, priority, maxAttempts, timeout, connection, idempotencyKey, reference, actor, onFailure) { … }` | schedules a job (`at` or `delay`, otherwise due now; `connection` joins the caller's transaction) and returns its id; with an [`idempotencyKey`](../jobs/README.md#idempotency-keys), the id of the existing job with the key; `reference` is the job's [reference](../jobs/README.md#references), `actor` the user named in its [history](../jobs/README.md#history) |
| `createJob(typedJob, payload, at, delay, priority, maxAttempts, timeout, connection, idempotencyKey, reference, actor, onFailure) { … }` | the same for a `TypedJob<T>`: the payload has the typed job's payload class, the optional handlers get it as `T` |
| `createRecurringJob(name, recurrence, payload, firstRun, delay, priority, maxAttempts, timeout, reference, actor, onFailure) { … }` | registers a recurring job and returns the id of its pending occurrence |
| `Bikesched.publishEvent(name, key, payload, retention, connection, idempotencyKey, reference, actor)` | publishes an event and returns its id; with an [`idempotencyKey`](../multi-step-jobs/events.md#idempotency-keys), the id of the earlier event with the key while it is kept; `reference` is the event's [reference](../multi-step-jobs/events.md#references), `actor` the user named in its history |
| `Job.publishEvent(name, key, payload, retention, stepName, reference)` | publishes once as a durable job step and returns the event's id, the same on every run; the event has the job's reference unless `reference` is given |
| `waitForEvent<T>(step, name, key, timeout, onFailure)` (in a handler) | waits for an event and returns its payload; once the `timeout` (or the default event timeout) passes, the job fails, unless `onFailure = { failing -> … }` resolves the wait with a fallback (e.g. `waitForEvent<Payment?>(…, timeout = 3.days, onFailure = { it.resolve(null) })`) |
| `step<T>(name, compensation, retryBackoff, onFailure) { … }` (in a handler) | runs a durable step; the optional `compensation = { step, result -> … }` undoes it if the job is cancelled or fails permanently; `onFailure = { failing -> … }` handles its failure in the job's last attempt and may `failing.resolve(x)` it with a fallback |
| `startJob(name, payload, stepName, at, delay, priority, maxAttempts, timeout, reference, compensation)` (in a handler) | starts a [child job](../multi-step-jobs/child-jobs.md) once as a durable step and returns its id, the same on every run; it has the parent's reference unless `reference` is given; the optional `compensation = { step, childId -> … }` e.g. cancels it |
| `poll<T>(name, interval, timeout, onFailure) { step -> … }` (in a handler) | polls until the trailing handler returns `Poll.ready(value)`, checking every `interval` (a `Duration` or a `RetryBackoff`); once the optional `timeout` passes, the job fails with a `PollTimeoutException`, unless `onFailure = { failing -> … }` resolves the poll (it also handles a check failing in the last attempt); a `poll<Unit>` stores no value |
| `awaitJob<T>(childJobId, timeout)` (in a handler) | waits for a child job and returns its `JobResult<T>`; `JobResult.TimedOut` once the optional `timeout` passed |
| `startJobBatch<P, R>(stepName, jobName, items, pairs, payloads, parallelism, failFast, priority, maxAttempts, timeout, reference, at, delay, compensation) { item(payload, key) }` (in a handler) | starts a [batch](../multi-step-jobs/child-jobs.md#batches) of `jobName` once as a durable step and returns the `JobBatch<R>`; items come from `items` (`BatchItem(payload, key)`), `pairs` (key to payload), `payloads` (keyed by position) and the lambda, in that order; `R = Unit` for a job without a result (`P = Unit` is rejected); the lambda runs on every run, the items are checked and mapped only on the first; `await()` returns the typed `JobBatchResult<R>` |
| `startJobBatch(stepName, items, …) { item(jobName, payload, key) }` (in a handler) | a mixed batch, each item naming its job (`MixedBatchItem(jobName, payload, key)`); `result<T>(key)` reads one item's `JobResult<T>` |

The optional trailing handler of `createJob` and `createRecurringJob` is registered for the name
unless it already has one; with a `payload` it gets the payload as its type, otherwise only the
`Job`. `onFailure = { payload -> … }` (with the `FailedJob` as receiver) registers the name's
failure handler the same way.

## Suspending functions

Job handlers, failure handlers, steps, polls, compensations and the `onFailure` handlers are
suspend lambdas: they call suspending functions directly, e.g. a Ktor client or a coroutine-based
repository:

```kotlin
bikesched.handleJobs<Order>("order") { order ->
    delay(1.seconds)                                 // any suspending call
    val quote = step<Quote>("quote") { shippingClient.quote(order) }   // a suspending HTTP call
    val prices = step<List<Int>>("prices") {
        coroutineScope { order.items.map { async { catalog.price(it) } }.awaitAll() }
    }
}
```

Each handler runs with `runBlocking` on the job's own virtual thread, so blocking it costs no
platform thread, and the job's MDC and its durable waits behave as in a blocking handler. A step,
poll or compensation runs in a nested `runBlocking` on the same thread.

- **Timeouts cancel the coroutine.** The job's timeout (and a stopping node) interrupts the job's
  thread, which cancels the handler's coroutine: `delay` and other suspending calls throw a
  `CancellationException`, and the attempt ends as timed out. An interrupt during a step cancels
  the step and the handler, also if the handler catches the step's exception. Code that ignores
  cancellation is abandoned by the hard timeout, as a blocking handler is.
- **Call blocking code directly.** The job's thread is a virtual thread, so blocking calls (JDBC,
  a blocking HTTP client) need no `Dispatchers.IO`; on the job's thread the timeout interrupts
  them. Cancelling `withContext(Dispatchers.IO) { … }` does not interrupt the blocking call on the
  IO thread, so the attempt then waits for it; use `runInterruptible(Dispatchers.IO) { … }` if you
  switch anyway. The MDC of the job does not follow a dispatcher switch either (add
  `MDCContext()` from `kotlinx-coroutines-slf4j` for that).
- **Steps run one at a time.** Coroutines the handler launches keep running while a step runs
  (the step's nested `runBlocking` runs them too), but only one step, event wait, sleep or poll of
  a job runs at a time: a second one started meanwhile fails the attempt with an
  `IllegalStateException`. Run concurrent work inside one step, as in the `prices` step above.
- **Don't swallow a wait.** An event wait, sleep or poll that is not ready ends the attempt with an
  internal `Error`. Let it propagate: `runCatching { waitForEvent(…) }` or a wait in a
  `supervisorScope { launch { … } }` swallows it, and the code after the wait runs although the job
  is suspended. Event waits, sleeps and polls hold no thread while they wait, so they need no
  suspending variant.

## Helpers for the Java API

| Helper | Instead of |
|---|---|
| `bikesched(dataSource) { … }` | `Bikesched.builder(dataSource)…build()` |
| `job.payload<T>()` | `payload(T.class)` |
| `schedule(Duration)`, `timeout(Duration)`, `retryAfter(Duration)`, `updateTimeout(Duration)`, `postpone(Duration)`, `sleep(String, Duration)`, `every(Duration)`, `everyAfterEnd(Duration)`, `fixedBackoff`, `exponentialBackoff` | `java.time.Duration` arguments |
| `findJobOrNull(id)` | `Optional` |

## Nullability

The Java API is annotated with [JSpecify](https://jspecify.dev/), so Kotlin sees its nullability
instead of platform types: `job.payload()` is `String?`, and a Java callback interface implemented
in Kotlin gets values that may be `null` as nullable types, e.g. the payload of a
`TypedJobHandler<Order>` lambda is `Order?`.

Reified helpers check nullability: `payload<Order>()` throws for a job without payload,
`payload<Order?>()` returns `null`. Likewise, a `handleJobs<Order>` handler is not called for a
job without payload (the attempt fails with an `IllegalStateException`), a `handleJobs<Order?>`
handler gets `null`.
