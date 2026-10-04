# bikesched

**A Java based, PostgreSQL first job scheduler with minimal dependencies**

bikesched stores jobs in PostgreSQL and lets every connected application instance (*node*) work on
them. Nodes claim due jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so many nodes can share one
database without stepping on each other and without any coordinator besides PostgreSQL.

It is inspired by libraries like [JobRunr](https://www.jobrunr.io) and
[db-scheduler](https://github.com/kagkarlsson/db-scheduler), but deliberately small:

- **Two runtime dependencies**: the PostgreSQL JDBC driver and the SLF4J API. No framework, no
  migration tool, no JSON library.
- **PostgreSQL first**: jobs, durable steps and node registrations live in plain tables
  (`bikesched_*`), the schema is created and migrated on startup.
- **Java 26**, jobs run on virtual threads.

> [!NOTE]
> bikesched is at an early stage (`0.1.0-SNAPSHOT`) and not published to a Maven repository yet.
> APIs and the database schema may still change without migrations.

## Features

- Schedule jobs to run now, at a given time or after a delay, with priority and retry limits
- Schedule jobs inside your own database transaction
- Automatic retries; failed attempts keep their error in `last_error`
- Durable **steps**: results of completed steps are stored and reused when a job is retried
- **Cancellation** from within a handler or via `Bikesched.cancel(jobId)`
- Optional **compensation** per step: completed steps are undone when a job is cancelled or fails
- **Timeouts** per job or per node, extendable by long-running handlers
- **Kotlin helpers** (`bikesched-kotlin`): configuration DSL, typed handlers and payloads via
  reified generics, `kotlin.time` durations
- Concurrency limit per node; jobs are only claimed when they can start right away
- Node heartbeats: jobs of crashed nodes are released and retried by others
- Typed payloads and step results through a pluggable `JsonMapper`
- Node, job and step identifiers in the SLF4J logging context (MDC)

## Quick start

```java
DataSource dataSource = ...; // any PostgreSQL DataSource, e.g. a HikariCP pool

try (Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("worker-1")              // optional, defaults to the host name
        .build()) {                    // migrates the schema, registers the node, starts polling

    // Register a handler for a job type ...
    bikesched.registerJobHandler("send-mail", job -> mailer.send(job.payload()));

    // ... and schedule jobs of that type
    bikesched.newJob("send-mail").payload("{\"to\": \"alice@example.com\"}").schedule();
}
```

Every node sharing the database polls all due jobs, regardless of their type, so all nodes should
register the same handlers. A handler that throws fails the attempt; the job is retried until its
maximum number of attempts is used up and then marked `FAILED`.

## Scheduling jobs

`newJob(type)` returns a builder; all settings are optional:

```java
UUID id = bikesched.newJob("report")
        .payload(new ReportRequest(42))           // converted by the JsonMapper
        .handler(job -> reports.create(job))      // registers the handler if none is registered yet
        .priority(10)                              // higher runs first, default 0
        .maxAttempts(5)                            // default 3
        .timeout(Duration.ofMinutes(10))           // default: the node's default timeout, if any
        .schedule();                               // or schedule(Instant) / schedule(Duration)
```

A builder can be reused: every `schedule…` call stores a new job.

To check on a job later, look it up by its id:

```java
Optional<JobInfo> job = bikesched.findJob(id);   // id(), type() and state(), e.g. JobState.SUCCEEDED
boolean done = job.map(info -> info.state().isEnded()).orElse(false);
```

### Transactional scheduling

Pass your own connection to create the job atomically with your other changes. The job becomes
visible to the nodes only once you commit, and disappears if you roll back:

```java
try (Connection connection = dataSource.getConnection()) {
    connection.setAutoCommit(false);
    insertOrder(connection, order);
    bikesched.newJob("send-confirmation").payload(json).connection(connection).schedule();
    connection.commit();
}
```

bikesched never commits, rolls back or closes your connection.

## Writing handlers

A handler receives the `Job` being executed:

| Method | Purpose |
|---|---|
| `id()`, `type()` | identity, e.g. for idempotency keys and logging |
| `attempts()`, `maxAttempts()` | the current attempt (starting at 1) and the limit |
| `payload()`, `payload(Class)` | the payload as raw JSON or mapped by the `JsonMapper` |
| `step(name, Class, handler)` | run a durable step, see [Durable steps](#durable-steps) |
| `newStep(name, Class)` | build a step with a compensation, see [Compensation](#compensation) |
| `waitForEvent(step, name, key)` | wait for an event, see [Waiting for events](#waiting-for-events) |
| `cancel()`, `cancel(message)` | cancel the job, see [Cancellation](#cancellation) |
| `fail()`, `fail(message)` | fail the attempt without throwing; `.remainingAttempts(n)` and `.retryAfter(Duration)` adjust the retry |
| `isCompensating()` | whether the handler is replayed to compensate the job |
| `updateTimeout(Duration)` | extend the deadline of the running attempt |

bikesched guarantees **at-least-once** execution: a job may run again, e.g. after its node
crashed, so handlers should be idempotent.

### Durable steps

Steps work like durable functions. A step that completed in an earlier attempt is not executed
again; its stored result is returned instead:

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Payment payment = job.step("charge-card", Payment.class, _ -> payments.charge(order));
    job.step("send-confirmation", Void.class, _ -> {
        mailer.send(order, payment);   // if this fails, the retry does not charge the card again
        return null;                   // Void steps store no result
    });
});
```

The result is mapped by the `JsonMapper` and stored in `bikesched_steps` right after the step
completes. Step names identify steps across attempts, so they must be unique within a job and
called in a deterministic way. The handler receives the `Step` (job id, name and attempt number),
e.g. for idempotency keys.

### Cancellation

```java
bikesched.registerJobHandler("send-mail", job -> {
    if (customerDeleted(job)) {
        job.cancel("customer deleted");   // stored as last_error
        return;                           // cancel() only marks the job, it does not stop the handler
    }
    mailer.send(job.payload());
});
```

A cancelled job ends in state `CANCELLED` and is not retried, even if the handler throws
afterwards.

Jobs can also be cancelled from outside, e.g. when the customer withdraws an order:

```java
boolean cancelled = bikesched.cancel(jobId, "order withdrawn");   // false if it already ended
```

The next node claiming the job cancels it instead of running its handler: right away if it is
scheduled or waiting for an event, after its current attempt if it is running (unless that attempt
completes it). Its unfinished steps, e.g. a wait for an event, become `CANCELLED` and no longer
receive events; completed steps are kept.

### Compensation

Steps with side effects can declare a compensation that undoes them if the job is cancelled or
fails permanently (a saga). Compensation is optional, per step:

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Reservation reservation = job.newStep("reserve-payment", Reservation.class)
            .compensateWith((step, r) -> payments.release(r))   // gets the step and its stored result
            .run(_ -> payments.reserve(order));
    job.step("ship", Void.class, _ -> { shipping.ship(order, reservation); return null; });
});

bikesched.cancel(orderJobId, "order withdrawn");   // releases the reservation if it was made
```

`newStep(name, Class).run(handler)` without `compensateWith` is the same as `step(...)`.

- A job without completed compensable steps is cancelled or failed right away, as before.
- Otherwise it becomes `COMPENSATING`. A node replays its handler in compensation mode: completed
  steps return their stored results and register their compensations, and the replay ends at the
  first step or event wait that has not completed. Then the compensations run, latest step first,
  and each compensated step becomes `COMPENSATED`. The job then ends `CANCELLED` or `FAILED`.
- A failing compensation is retried (up to `maxAttempts` compensation attempts, with the node's
  retry backoff); compensations that already succeeded are not run again. After that the job is
  `COMPENSATION_FAILED` and needs manual attention.
- Compensations run at least once, so they must be idempotent. The handler's code outside of
  steps runs again during the replay; `job.isCompensating()` tells it apart.

### Recurring jobs

```java
bikesched.newJob("cleanup")
        .recurring(Schedule.daily(LocalTime.of(3, 0), ZoneId.of("Europe/Berlin")))
        .handler(job -> cleanup())
        .schedule();   // idempotent: every node registers it on startup
```

- The job type identifies the recurring job. Each run is an *occurrence*, a regular job with its
  own retries, timeout, steps and events. At most one occurrence is pending at a time, enforced by
  a partial unique index.
- When an occurrence ends (succeeded, permanently failed, or cancelled, which cancels only that
  occurrence), the next one is inserted in the same transaction. Runs missed while no node was up
  are skipped.
- Schedules: `Schedule.every(Duration)`, `daily(LocalTime, ZoneId)`,
  `weekly(DayOfWeek, LocalTime, ZoneId)`, or `Schedule.of(description, previous -> next)`.
- The next run is computed from the previous occurrence's scheduled time. Schedules whose
  `isFixedRate()` returns `true` (by default only `every`) also store each occurrence's planned
  time (`fixed_rate_at`) and compute from that instead, so retries and event waits don't shift a
  fixed rate.
- Each occurrence stores the schedule's description. Registering a schedule with a different
  description (e.g. after a deploy) moves the pending occurrence to the new schedule.
- Registering a recurring job recreates a missing occurrence; a node also checks all its recurring
  jobs once when it starts polling, retried every `nodeCleanUpInterval` if that fails.
  `bikesched.removeRecurringJob(type)` stops a recurring job that is no longer registered anywhere.
- `recurring(...)` and `connection(...)` lead to different builders, so a recurring job can't be
  scheduled in the caller's transaction.

### Waiting for events

A handler can wait for an event as a durable step. If the event hasn't been published yet, the
attempt ends and the job is suspended in state `WAITING`, without holding a thread or a
concurrency slot. Publishing the event resumes it, and the handler runs again from the top:
completed steps are skipped, and the wait now returns the event.

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
            .receive(Payment.class);
    job.step("ship", Void.class, _ -> { shipping.ship(order, payment); return null; });
});

// e.g. in a payment webhook; .connection(connection) publishes in your own transaction
List<ReceivedEvent> received = bikesched.newEvent("payment-received", order.id())
        .payload(payment)
        .publish();   // the steps that received the event, with their job ids
```

To stop waiting after a while, add a timeout; its handler's result is returned instead of the
event:

```java
Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
        .<Payment>timeout(Duration.ofDays(3), () -> null)   // the type is needed for a null result
        .receive(Payment.class);
```

- Events are stored in `bikesched_events` for the `eventRetention` period (default 7 days).
  A step that starts waiting after the event was published receives the latest one right away.
- An event goes to every step waiting for it. `receive(event -> …)` gets the whole `Event` (name,
  key, payload) instead of the mapped payload.
- Without a timeout of its own, a wait uses the node's `defaultEventTimeout`, if set: once it
  passes, the job fails permanently with an `EventTimeoutException`.
- Waiting does not use up an attempt.
- Code outside steps runs again on every resume, so it must be cheap and deterministic.
- Waiting, suspending and publishing lock the event's name and key for the length of their
  transaction, so an event published while a job starts waiting is never missed.

### Timeouts

When an attempt exceeds its timeout, bikesched logs a warning and interrupts the handler's
thread. Once the handler returns or throws, the attempt counts as failed and is retried while
attempts remain. A handler that is still making progress can extend its deadline:

```java
while (!encoder.isDone(task)) {
    job.updateTimeout(Duration.ofMinutes(2));   // two more minutes from now
    Thread.sleep(Duration.ofSeconds(10));
}
```

Interrupting only stops handlers that react to it, e.g. blocking calls like `Thread.sleep` or I/O
on virtual threads.

## Configuration

| Builder option | Default | Description |
|---|---|---|
| `dataSource(DataSource)` | required | PostgreSQL data source; tables are created in its default schema |
| `nodeName(String)` | host name | unique node name; a dead node's name is taken over |
| `maxConcurrentJobs(int)` | 10 | jobs executed at the same time on this node |
| `autoStartPolling(boolean)` | `true` | with `false`, call `startPolling()` once all handlers are registered; until then the node is not registered, executes no jobs and sends no heartbeats, e.g. for an instance that only schedules jobs. `build()` then does not access the database, so it works while the database is down: the schema is migrated on first use (`startPolling()`, scheduling a job, publishing an event), and retried on the next one if that fails |
| `defaultJobTimeout(Duration)` | none | timeout for jobs scheduled without one |
| `retryBackoff(RetryBackoff)` | `RetryBackoff.none()` | delay before retrying a failed attempt, e.g. `RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(10))`; a handler's `job.fail().retryAfter(…)` takes precedence |
| `jsonMapper(JsonMapper)` | `JsonMapper.rawJson()` | maps payloads and step results to and from JSON |
| `eventRetention(Duration)` | 7 days | how long published events are kept for steps that start waiting later |
| `eventCleanUpInterval(Duration)` | 1 hour | how often the node deletes events older than the retention |
| `nodeCleanUpInterval(Duration)` | 15 seconds | how often the node releases the running jobs of dead nodes and removes dead nodes; also the retry delay of the one-time check for missing occurrences of its recurring jobs |
| `defaultEventTimeout(Duration)` | none | maximum wait for events without a `.timeout(...)` of their own; afterwards the job fails with an `EventTimeoutException` |

Nodes send a heartbeat every 5 seconds and poll for due jobs every second, claiming at most 10
jobs per poll and never more than they have free slots. A node without heartbeat for 30 seconds is
considered dead: on their next node clean-up, other nodes release its running jobs for retry, and
remove the node after another 30 seconds. The heartbeat itself only updates the node's row; the
clean-ups run on their own thread and intervals, so they never delay it.

### JSON mapping

bikesched ships without a JSON library. The default `JsonMapper.rawJson()` only accepts strings that
already contain JSON. Plug in the library of your choice to use typed payloads and step results,
e.g. Jackson:

```java
ObjectMapper objectMapper = new ObjectMapper();
Bikesched.builder()
        .dataSource(dataSource)
        .jsonMapper(new JsonMapper() {
            public String toJson(Object value) {
                try {
                    return objectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(e);
                }
            }

            public <T> T fromJson(String json, Class<T> type) {
                try {
                    return objectMapper.readValue(json, type);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        })
        .build();
```

### Logging

bikesched logs through the SLF4J API; bring your own binding. On its own threads it fills the
logging context with the keys from `BikeschedLogContext`: `bikesched.nodeId`, `bikesched.jobId`,
`bikesched.jobType` and `bikesched.stepName`, each only where it applies. With Logback:

```xml
<pattern>%d %-5level [%thread] node=%X{bikesched.nodeId} job=%X{bikesched.jobId} %logger - %msg%n</pattern>
```

On startup bikesched logs the database URL (without credentials) at `DEBUG` as
`Database: jdbc:postgresql://…`, which IDE plugins such as the
[Testcontainers Port Updater](https://github.com/yusufugurozbek/testcontainers-port-updater) pick up.

## Kotlin

The `bikesched-kotlin` module adds Kotlin extensions on top of the Java API; it depends on
`bikesched-core` and the Kotlin standard library only.

```kotlin
val bikesched = bikesched {                         // instead of Bikesched.builder()…build()
    dataSource = ds
    jsonMapper = myJsonMapper                       // e.g. Jackson with its Kotlin module
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
bikesched.createRecurringJob(type = "cleanup", schedule = every(1.hours)) { cleanup.run() }
```

`createJob`, `createRecurringJob`, `publishEvent` and `waitForEvent` take named arguments. Only the
type (resp. step, name and key) and the schedule are required; omitted options keep the builder
defaults:

| Function | Arguments |
|---|---|
| `handleJobs<T>(type) { payload -> }`, `handleJobs(type) { }` | registers the handler; the `Job` is the receiver |
| `createJob(type, payload, at, delay, priority, maxAttempts, timeout, connection) { … }` | schedules a job (`at` or `delay`, otherwise due now; `connection` joins the caller's transaction) and returns its id |
| `createRecurringJob(type, schedule, payload, firstRun, delay, priority, maxAttempts, timeout) { … }` | registers a recurring job and returns the id of its pending occurrence |
| `publishEvent(name, key, payload, connection)` | publishes an event and returns the steps that received it |
| `waitForEvent<T>(step, name, key, timeout) { fallback }` (in a handler) | waits for an event and returns its payload; once the `timeout` passes, the fallback's result (e.g. `waitForEvent<Payment?>(…, timeout = 3.days) { null }`), without fallback the job fails permanently like with the default event timeout |
| `step<T>(name, compensation) { … }` (in a handler) | runs a durable step; the optional `compensation = { step, result -> … }` undoes it if the job is cancelled or fails permanently |

The optional trailing handler of `createJob` and `createRecurringJob` is registered for the type unless it
already has one; with a `payload` it gets the payload as its type, otherwise only the `Job`.

Further helpers for the Java API:

| Helper | Instead of |
|---|---|
| `bikesched { … }` | `Bikesched.builder()…build()` |
| `job.payload<T>()`, `event.payload<T>()` | `payload(T.class)` |
| `receive<T>()`, `receive<T>(timeout) { fallback }`, `receiveOrNull<T>(timeout)` on the Java `waitForEvent(step, name, key)` | `receive(T.class)`, `timeout(…, handler).receive(T.class)` |
| `schedule(Duration)`, `timeout(Duration)`, `retryAfter(Duration)`, `updateTimeout(Duration)`, `every(Duration)`, `fixedBackoff`, `exponentialBackoff` | `java.time.Duration` arguments |
| `findJobOrNull(id)`, `node.idOrNull` | `Optional` |

Reified helpers check nullability: `payload<Order>()` throws for a job without payload,
`payload<Order?>()` returns `null`. Likewise, a `handleJobs<Order>` handler is not called for a
job without payload (the attempt fails with an `IllegalStateException`), a `handleJobs<Order?>`
handler gets `null`.

## Database

The schema is created by bikesched's own minimal migrator on startup; concurrent nodes are
serialized with a PostgreSQL advisory lock, applied migrations are tracked with checksums in
`bikesched_schema_migrations`.

| Table | Content |
|---|---|
| `bikesched_schema_migrations` | applied schema migrations with version, description, checksum, install time and duration; created by the migrator itself |
| `bikesched_nodes` | registered nodes with their unique name and last heartbeat; dead nodes are removed by the others |
| `bikesched_jobs` | jobs with state `SCHEDULED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `COMPENSATING` or `COMPENSATION_FAILED`, their payload, priority, attempts, timeout, lock (`locked_by`) and `last_error`; compensating jobs also carry the state they end in (`compensated_state`) and their `compensation_attempts`, jobs cancelled via `Bikesched.cancel` their `cancel_requested_at` and `cancel_message`; occurrences of recurring jobs also carry their `schedule` description and, for fixed-rate schedules, their `fixed_rate_at` |
| `bikesched_steps` | durable steps of each job with state `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `COMPENSATED` or `CANCELLED`, their result, attempts and whether they have a compensation (`compensable`); steps waiting for an event also carry `event_name`, `event_key` and their deadline (`wait_until`) |
| `bikesched_events` | published events (name, key, payload), kept for the event retention period |

Besides primary keys, the indexes cover polling (due `SCHEDULED`/`WAITING`/`COMPENSATING` jobs in claim order),
jobs per node, at most one pending occurrence per recurring job (partial unique index), steps
waiting for an event, and event lookup and cleanup. Waiting for, suspending at and publishing
events also take transaction-scoped advisory locks per event name and key; no table is involved.

## Examples

The `bikesched-examples` module contains runnable examples, each covered by a test against
PostgreSQL:

- **Taxi booking** ([`org.bikesched.examples.taxi`](bikesched-examples/src/main/java/org/bikesched/examples/taxi)):
  a ride request is sent to a taxi broker, waits for a driver to accept it with a price and for the
  customer to accept that price, then reserves the payment, approves the ride at the broker and
  sends the customer a push notification with the estimated time of arrival. One job with durable
  steps and event waits. Without a driver or an answer in time, if the customer declines the price
  or cancels the ride (`Bikesched.cancel`), or if the broker cannot approve it, the ride is
  cancelled resp. fails, and compensations release the payment and withdraw the ride at the
  broker. Payloads and events are records, mapped with Jackson via `JacksonJsonMapper`.

```shell
docker compose up -d
./gradlew :bikesched-examples:run         # honors POSTGRES_PORT as well
```

## Building

Requirements: JDK 26 (or let Gradle provision it via the toolchain) and Docker for the tests,
which run against PostgreSQL 18 with [Testcontainers](https://testcontainers.com).

```shell
./gradlew build
```

To try it out locally, start PostgreSQL with Docker Compose and run the demo `Starter`, which
schedules a few jobs and prints them as they are executed:

```shell
docker compose up -d                      # POSTGRES_PORT=5433 docker compose up -d if 5432 is taken
./gradlew :bikesched-core:runStarter      # honors POSTGRES_PORT as well
```

All dependency and tool versions are managed in `gradle/libs.versions.toml`.
