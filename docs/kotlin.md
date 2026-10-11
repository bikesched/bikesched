---
title: "Kotlin"
weight: 8
---

# Kotlin

The `bikesched-kotlin` module adds Kotlin extensions on top of the Java API, in the package
`org.bikesched.kotlin`; it depends on `bikesched-core` and the Kotlin standard library only.

```kotlin
// bikesched-jackson with Jackson's Kotlin module (tools.jackson.module:jackson-module-kotlin)
val kotlinObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

val bikesched = bikesched {                         // instead of Bikesched.builder()…build()
    dataSource = ds
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

bikesched.createJob(type = "order", payload = Order("o-1", 100))
bikesched.publishEvent(name = "payment-received", key = "o-1", payload = payment)
bikesched.createRecurringJob(type = "cleanup", recurrence = every(1.hours)) { cleanup.run() }
```

## Functions

`createJob`, `createRecurringJob`, `publishEvent`, `waitForEvent` and `startJob` take named
arguments. Only the
type (resp. step, name and key) and the recurrence are required; omitted options keep the builder
defaults:

| Function | Arguments |
|---|---|
| `handleJobs<T>(type) { payload -> }`, `handleJobs(type) { }` | registers the handler; the `Job` is the receiver |
| `handleJobFailures<T>(type) { payload -> }`, `handleJobFailures(type) { }` | registers the [failure handler](jobs.md#failure-handlers); the `FailedJob` is the receiver |
| `createJob(type, payload, at, delay, priority, maxAttempts, timeout, connection, idempotencyKey, onFailure) { … }` | schedules a job (`at` or `delay`, otherwise due now; `connection` joins the caller's transaction) and returns its id; with an [`idempotencyKey`](jobs.md#idempotency-keys), the id of the existing job with the key |
| `createJob(jobType, payload, at, delay, priority, maxAttempts, timeout, connection, idempotencyKey, onFailure) { … }` | the same for a `JobType<T>`: the payload has the type's payload class, the optional handlers get it as `T` |
| `createRecurringJob(type, recurrence, payload, firstRun, delay, priority, maxAttempts, timeout, onFailure) { … }` | registers a recurring job and returns the id of its pending occurrence |
| `Bikesched.publishEvent(name, key, payload, retention, connection, idempotencyKey)` | publishes an event and returns its id; with an [`idempotencyKey`](events.md#idempotency-keys), the id of the earlier event with the key while it is kept |
| `Job.publishEvent(name, key, payload, retention, stepName)` | publishes once as a durable job step and returns the event's id, the same on every run |
| `waitForEvent<T>(step, name, key, timeout, onFailure)` (in a handler) | waits for an event and returns its payload; once the `timeout` (or the default event timeout) passes, the job fails, unless `onFailure = { failing -> … }` resolves the wait with a fallback (e.g. `waitForEvent<Payment?>(…, timeout = 3.days, onFailure = { it.resolve(null) })`) |
| `step<T>(name, compensation, retryBackoff, onFailure) { … }` (in a handler) | runs a durable step; the optional `compensation = { step, result -> … }` undoes it if the job is cancelled or fails permanently; `onFailure = { failing -> … }` handles its failure in the job's last attempt and may `failing.resolve(x)` it with a fallback |
| `startJob(type, payload, stepName, at, delay, priority, maxAttempts, timeout, compensation)` (in a handler) | starts a [child job](child-jobs.md) once as a durable step and returns its id, the same on every run; the optional `compensation = { step, childId -> … }` e.g. cancels it |
| `poll<T>(name, interval, timeout, onFailure) { step -> … }` (in a handler) | polls until the trailing handler returns `Poll.ready(value)`, checking every `interval` (a `Duration` or a `RetryBackoff`); once the optional `timeout` passes, the job fails with a `PollTimeoutException`, unless `onFailure = { failing -> … }` resolves the poll (it also handles a check failing in the last attempt); a `poll<Unit>` stores no value |
| `awaitJob<T>(childJobId, timeout)` (in a handler) | waits for a child job and returns its `JobResult<T>`; `JobResult.TimedOut` once the optional `timeout` passed |

The optional trailing handler of `createJob` and `createRecurringJob` is registered for the type
unless it already has one; with a `payload` it gets the payload as its type, otherwise only the
`Job`. `onFailure = { payload -> … }` (with the `FailedJob` as receiver) registers the type's
failure handler the same way.

## Helpers for the Java API

| Helper | Instead of |
|---|---|
| `bikesched { … }` | `Bikesched.builder()…build()` |
| `job.payload<T>()` | `payload(T.class)` |
| `schedule(Duration)`, `timeout(Duration)`, `retryAfter(Duration)`, `updateTimeout(Duration)`, `postpone(Duration)`, `sleep(String, Duration)`, `every(Duration)`, `fixedBackoff`, `exponentialBackoff` | `java.time.Duration` arguments |
| `findJobOrNull(id)`, `node.idOrNull` | `Optional` |

## Nullability

Reified helpers check nullability: `payload<Order>()` throws for a job without payload,
`payload<Order?>()` returns `null`. Likewise, a `handleJobs<Order>` handler is not called for a
job without payload (the attempt fails with an `IllegalStateException`), a `handleJobs<Order?>`
handler gets `null`.
