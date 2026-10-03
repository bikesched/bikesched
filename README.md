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
- **Cancellation** from within a handler
- **Timeouts** per job or per node, extendable by long-running handlers
- Concurrency limit per node; jobs are only claimed when they can start right away
- Node heartbeats: jobs of crashed nodes are released and retried by others
- Typed payloads and step results through a pluggable `JsonMapper`
- Node, job and step identifiers in the SLF4J logging context (MDC)

## Quick start

```java
DataSource dataSource = ...; // any PostgreSQL DataSource, e.g. a HikariCP pool

try (Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .node("worker-1")              // optional, defaults to the host name
        .build()) {                    // migrates the schema, registers the node, starts polling

    // Register a handler for a job type ...
    bikesched.registerHandler("send-mail", job -> mailer.send(job.payload()));

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
| `step(…)` | run a durable step |
| `cancel()`, `cancel(message)` | cancel the job |
| `updateTimeout(Duration)` | extend the deadline of the running attempt |

bikesched guarantees **at-least-once** execution: a job may run again, e.g. after its node
crashed, so handlers should be idempotent.

### Durable steps

Steps work like durable functions. A step that completed in an earlier attempt is not executed
again; its stored result is returned instead:

```java
bikesched.registerHandler("order", job -> {
    Order order = job.payload(Order.class);
    Payment payment = job.step("charge-card", Payment.class, step -> payments.charge(order));
    job.step("send-confirmation", Void.class, step -> {
        mailer.send(order, payment);   // if this fails, the retry does not charge the card again
        return null;
    });
});
```

Step names identify steps across attempts, so they must be unique within a job and called in a
deterministic way. Results are stored in `bikesched_steps` right after the step completes.

### Cancellation

```java
bikesched.registerHandler("send-mail", job -> {
    if (customerDeleted(job)) {
        job.cancel("customer deleted");   // stored as last_error
        return;                           // cancel() only marks the job, it does not stop the handler
    }
    mailer.send(job.payload());
});
```

A cancelled job ends in state `CANCELLED` and is not retried, even if the handler throws
afterwards.

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
  `usePlannedTime()` returns `true` (by default only `every`) also store each occurrence's planned
  time (`planned_at`) and compute from that instead, so retries and event waits don't shift a fixed
  rate.
- Each occurrence stores the schedule's description. Registering a schedule with a different
  description (e.g. after a deploy) moves the pending occurrence to the new schedule.
- Nodes recreate a missing occurrence of their recurring jobs on every heartbeat.
  `bikesched.removeRecurringJob(type)` stops a recurring job that is no longer registered anywhere.
- `recurring(...)` and `connection(...)` lead to different builders, so a recurring job can't be
  scheduled in the caller's transaction.

### Waiting for events

A handler can wait for an event as a durable step. If the event hasn't been published yet, the
attempt ends and the job is suspended in state `WAITING`, without holding a thread or a
concurrency slot. Publishing the event resumes it, and the handler runs again from the top:
completed steps are skipped, and the wait now returns the event.

```java
bikesched.registerHandler("order", job -> {
    Order order = job.payload(Order.class);
    job.step("create-invoice", Invoice.class, step -> billing.createInvoice(order));
    Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
            .timeout(Duration.ofDays(3), () -> Payment.missing())   // optional
            .receive(event -> event.payload(Payment.class));        // or .receive(Payment.class)
    job.step("ship", Void.class, step -> { shipping.ship(order, payment); return null; });
});

// e.g. in a payment webhook, optionally in your own transaction via .connection(connection)
List<Step> received = bikesched.newEvent("payment-received", orderId).payload(payment).publish();
```

- Events are stored in `bikesched_events` for the `eventRetention` period (default 7 days).
  A step that starts waiting after the event was published receives the latest one right away.
- An event goes to every step waiting for it.
- With `.timeout(duration, timeoutHandler)`, `receive` returns the timeout handler's result once the
  deadline has passed.
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
| `node(String)` | host name | unique node name; a dead node's name is taken over |
| `maxConcurrentJobs(int)` | 10 | jobs executed at the same time on this node |
| `autoStartPolling(boolean)` | `true` | with `false`, call `startPolling()` once all handlers are registered; until then the node is not registered, executes no jobs and sends no heartbeats, e.g. for an instance that only schedules jobs. `build()` then does not access the database, so it works while the database is down: the schema is migrated on first use (`startPolling()`, scheduling a job, publishing an event), and retried on the next one if that fails |
| `defaultJobTimeout(Duration)` | none | timeout for jobs scheduled without one |
| `retryBackoff(RetryBackoff)` | `RetryBackoff.none()` | delay before retrying a failed attempt, e.g. `RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(10))`; a handler's `job.fail().retryAfter(…)` takes precedence |
| `jsonMapper(JsonMapper)` | `JsonStringMapper` | maps payloads and step results to and from JSON |
| `eventRetention(Duration)` | 7 days | how long published events are kept for steps that start waiting later |
| `defaultEventTimeout(Duration)` | none | maximum wait for events without a `.timeout(...)` of their own; afterwards the job fails with an `EventTimeoutException` |

Nodes send a heartbeat every 5 seconds and poll for due jobs every second, claiming at most 10
jobs per poll and never more than they have free slots. A node without heartbeat for 30 seconds is
considered dead: other nodes release its running jobs for retry and remove the node after another
30 seconds.

### JSON mapping

bikesched ships without a JSON library. The default `JsonStringMapper` only accepts strings that
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

## Database

The schema is created by bikesched's own minimal migrator on startup; concurrent nodes are
serialized with a PostgreSQL advisory lock, applied migrations are tracked with checksums in
`bikesched_schema_migrations`.

| Table | Content |
|---|---|
| `bikesched_schema_migrations` | applied schema migrations with version, description, checksum, install time and duration; created by the migrator itself |
| `bikesched_nodes` | registered nodes with their unique name and last heartbeat; dead nodes are removed by the others |
| `bikesched_jobs` | jobs with state `SCHEDULED`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED` or `CANCELLED`, their payload, priority, attempts, timeout, lock (`locked_by`) and `last_error`; occurrences of recurring jobs also carry their `schedule` description and, for fixed-rate schedules, their `planned_at` |
| `bikesched_steps` | durable steps of each job with state `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED` or `TIMED_OUT`, their result and attempts; steps waiting for an event also carry `event_name`, `event_key` and their deadline (`wait_until`) |
| `bikesched_events` | published events (name, key, payload), kept for the event retention period |

Besides primary keys, the indexes cover polling (due `SCHEDULED`/`WAITING` jobs in claim order),
jobs per node, at most one pending occurrence per recurring job (partial unique index), steps
waiting for an event, and event lookup and cleanup. Waiting for, suspending at and publishing
events also take transaction-scoped advisory locks per event name and key; no table is involved.

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
