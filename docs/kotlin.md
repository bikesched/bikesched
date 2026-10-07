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
    step<Unit>("ship") { shipping.ship(order, reservation, payment) }   // Unit steps store no result
}

bikesched.createJob(type = "order", payload = Order("o-1", 100))
bikesched.publishEvent(name = "payment-received", key = "o-1", payload = payment)
bikesched.createRecurringJob(type = "cleanup", recurrence = every(1.hours)) { cleanup.run() }
```

## Functions

`createJob`, `createRecurringJob`, `publishEvent` and `waitForEvent` take named arguments. Only the
type (resp. step, name and key) and the recurrence are required; omitted options keep the builder
defaults:

| Function | Arguments |
|---|---|
| `handleJobs<T>(type) { payload -> }`, `handleJobs(type) { }` | registers the handler; the `Job` is the receiver |
| `createJob(type, payload, at, delay, priority, maxAttempts, timeout, connection) { … }` | schedules a job (`at` or `delay`, otherwise due now; `connection` joins the caller's transaction) and returns its id |
| `createRecurringJob(type, recurrence, payload, firstRun, delay, priority, maxAttempts, timeout) { … }` | registers a recurring job and returns the id of its pending occurrence |
| `publishEvent(name, key, payload, retention, connection)` | publishes an event and returns the steps that received it |
| `waitForEvent<T>(step, name, key, timeout) { fallback }` (in a handler) | waits for an event and returns its payload; once the `timeout` passes, the fallback's result (e.g. `waitForEvent<Payment?>(…, timeout = 3.days) { null }`), without fallback the job fails permanently like with the default event timeout |
| `step<T>(name, compensation) { … }` (in a handler) | runs a durable step; the optional `compensation = { step, result -> … }` undoes it if the job is cancelled or fails permanently |

The optional trailing handler of `createJob` and `createRecurringJob` is registered for the type
unless it already has one; with a `payload` it gets the payload as its type, otherwise only the
`Job`.

## Helpers for the Java API

| Helper | Instead of |
|---|---|
| `bikesched { … }` | `Bikesched.builder()…build()` |
| `job.payload<T>()` | `payload(T.class)` |
| `receive<T>()`, `receive<T>(timeout) { fallback }`, `receiveOrNull<T>(timeout)` on the Java `waitForEvent(step, name, key)` | `receive(T.class)`, `timeout(…, handler).receive(T.class)` |
| `schedule(Duration)`, `timeout(Duration)`, `retryAfter(Duration)`, `updateTimeout(Duration)`, `sleep(String, Duration)`, `every(Duration)`, `fixedBackoff`, `exponentialBackoff` | `java.time.Duration` arguments |
| `findJobOrNull(id)`, `node.idOrNull` | `Optional` |

## Nullability

Reified helpers check nullability: `payload<Order>()` throws for a job without payload,
`payload<Order?>()` returns `null`. Likewise, a `handleJobs<Order>` handler is not called for a
job without payload (the attempt fails with an `IllegalStateException`), a `handleJobs<Order?>`
handler gets `null`.

---

Next: [Annotations](annotations.md) · Previous: [Configuration](configuration.md) ·
[Documentation index](index.md)
