---
title: "Configuration"
weight: 1
---

# Configuration

A node is configured with `Bikesched.builder()`; only the data source is required.

```java
Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .build();
```

The returned instance is already registered and polling: it starts working right away. Set
`autoStartPolling(false)` to defer this, e.g. to register handlers first, and call `start()`
afterwards (see `autoStartPolling` below).

## Builder options

| Builder option | Default | Description |
|---|---|---|
| `dataSource(DataSource)` | required | PostgreSQL data source; tables are created in its default schema |
| `nodeName(String)` | host name | node name in logs and the admin UI; need not be unique, see [Nodes](#nodes) |
| `maxConcurrentJobs(int)` | 10 | jobs executed at the same time on this node |
| `migrateSchema(boolean)` | `true` | automatically migrate on first database use; `false` only validates migration history without DDL. Explicit `migrate()` still migrates. See [Migrations](../operations/database.md#migrations) |
| `autoStartPolling(boolean)` | `true` | with `false`, call `start()` once all handlers are registered; until then the node is not registered, executes no jobs and sends no heartbeats, e.g. for an instance that only schedules jobs. `build()` then does not access the database, so it works while the database is down: the schema is migrated (or validated with `migrateSchema(false)`) on first use (`start()`, scheduling a job, publishing an event), and retried on the next one if that fails |
| `pollInterval(Duration)` | 10 seconds | delay between two polls for due jobs. While nodes [listen for due jobs](#due-jobs-start-right-away), it is the safety net for missed notifications and bounds how late a delayed job scheduled by another node starts; without listening, every job made due by another node starts within one interval, so lower it (e.g. 5 seconds or less) |
| `listenForDueJobs(boolean)` | `true` | with `false`, the node neither listens for due jobs nor notifies other nodes, and holds no extra connection; set it the same on all nodes, see [Due jobs start right away](#due-jobs-start-right-away) |
| `notifyInterval(Duration)` | 100 ms | how long a node collects the job types it made due before notifying the other nodes in one statement: the extra latency of such jobs on other nodes, and at most one notifying commit per node and interval |
| `heartbeatInterval(Duration)` | 5 seconds | interval between the node's heartbeats; at most half the `nodeTimeout` |
| `nodeTimeout(Duration)` | 30 seconds | time without heartbeat after which other nodes consider the node dead and release its jobs; the builder rejects a value shorter than twice the `heartbeatInterval` |
| `defaultJobTimeout(Duration)` | none | timeout for jobs scheduled without one |
| `retryBackoff(RetryBackoff)` | `RetryBackoff.none()` | delay before retrying a failed attempt, e.g. `RetryBackoff.fixed(Duration.ofSeconds(30))` or `RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(10))` (doubling, each delay randomly shortened by up to half); a handler's `job.fail().retryAfter(…)` takes precedence |
| `failureHandlerAttempts(int)` | 3 | how often a [job's](../jobs/README.md#failure-handlers) or [step's](../multi-step-jobs/steps.md#failure-handler-per-step) failure handler runs before it is given up; a run counts once it started, so a run that crashes the node counts too |
| `jsonMapper(JsonMapper)` | JSON strings only | maps payloads, step results and event payloads to and from JSON, see [JSON mapping](#json-mapping) |
| `eventRetention(Duration)` | 7 days | how long events published through this node are kept for steps that start waiting later, unless an event sets its own `retention(...)`, see [Retention](../multi-step-jobs/events.md#retention) |
| `eventCleanUpInterval(Duration)` | 1 hour | how often the node deletes expired events |
| `nodeCleanUpInterval(Duration)` | 15 seconds | how often the node releases the running jobs of dead nodes and removes dead nodes; also the retry delay of the one-time check for missing occurrences of its recurring jobs |
| `defaultEventTimeout(Duration)` | none | maximum wait for events without a `.timeout(...)` of their own; afterwards the job fails with an `EventTimeoutException`, unless the wait's `onFailure(…)` resolves it |
| `jobHistory(Duration retention)` | off | records every change of jobs and their steps in `bikesched_job_history` and keeps it for the retention, see [Job history](../jobs/README.md#job-history); enable it on all nodes |
| `jobHistoryCleanUpInterval(Duration)` | 1 hour | how often a node with the job history deletes entries older than the retention |
| `jobRetention(Duration)` | 7 days | deletes ended jobs (`SUCCEEDED`, `FAILED`, `CANCELLED`) with their steps once they finished longer ago, see [Job retention](../jobs/README.md#job-retention) |
| `jobCleanUpInterval(Duration)` | 1 hour | how often the node deletes ended jobs older than the retention |
| `statementTimeout(Duration)` | data source | maximum duration of each database statement of the node, applied as JDBC query timeout (whole seconds, rounded up); a statement running longer is cancelled and fails like any other database error, see [Statement timeout](#statement-timeout) |
| `recurrenceParsers(RecurrenceParser...)` | `RecurrenceParser.isoDuration()` | parse the expressions of `schedule(String)` and `@JobHandler(schedule = …)`; replaces the default, see [Recurrence expressions](../jobs/recurring-jobs.md#recurrence-expressions) |
| `jobListener(JobListener)` | none | observes claims and attempt outcomes, e.g. for [Metrics](../operations/metrics.md) |
| `eventListener(EventListener)` | none | observes published events (not those in the caller's transaction), e.g. for [Metrics](../operations/metrics.md) |
| `hardTimeoutGrace(Duration)` | off | abandons attempts whose handler ignores the interrupt of its timeout this long after the timeout, see [Timeouts](../jobs/README.md#timeouts) |

## Nodes

A node sends a heartbeat (every 5 seconds) and polls for due jobs (every 10 seconds), claiming up
to `maxConcurrentJobs` at a time; see [Builder options](#builder-options) and [Due jobs start right
away](#due-jobs-start-right-away) for the exact timing. It only claims jobs of types it has
handlers for, so during a rolling deploy old nodes leave new job types to new nodes.

A node without a heartbeat for 30 seconds (`nodeTimeout`) is considered dead: other nodes release
its running jobs for retry on their next node clean-up and remove it after another 30 seconds. A
dead node's writes can't do any more harm even if it is still running: every claim gets a fresh
lock token, and the first step, event wait or compensation a stale attempt reaches fails, abandoning
it with a warning (work outside steps is not covered).

A node that was considered dead while it was still running, e.g. after a pause longer than twice
the `nodeTimeout`, finds itself removed with its next heartbeat and registers again under its id
and name.

Every node registers under a new id; its name only labels it in logs and the admin UI and need not
be unique. An instance restarted right after a crash therefore starts next to the stale entry of
its previous run: that entry turns dead after the `nodeTimeout`, and the node clean-up releases its
jobs and removes it like any other dead node.

`bikesched.node()` describes the node (`name()`, `id()`, `isPolling()`). `close()` stops polling and
the heartbeat, waits up to 10 seconds for running jobs, returns still-running jobs to `SCHEDULED` for
other nodes, and deregisters the node.

## Due jobs start right away

A job starts when it is due, not on the next poll: a node that makes jobs due now (schedules a
job, publishes an event a job waits for, ends a child job, retries, cancels or releases jobs) tells
every node through PostgreSQL's `LISTEN/NOTIFY`, and every node wakes itself up for the delayed
work it writes itself (sleeps, polls, event-wait deadlines, retries with a delay, next occurrences
of recurring jobs, delayed jobs). With every interval poll, a node also looks for jobs of its
types that become due before its next interval poll, e.g. delayed jobs scheduled by another node.

| Job made due | Starts |
|---|---|
| now, by this node | right away |
| now, by another node | within the `notifyInterval` (100 ms) |
| later, by this node (sleep, poll, retry, deadline, occurrence, delayed job) | when due |
| later, by another node or on a caller's connection | when due, if the next interval poll saw it in time; otherwise up to one `pollInterval` late |
| by plain SQL, outside bikesched | on the next interval poll |

- **Batched notifications.** A transaction that sends a notification takes a database-wide lock at
  its commit (PostgreSQL serializes notifying commits; a few thousand per second on fast local
  disks). bikesched therefore never notifies inside a job write: each node collects the types it
  made due and notifies them in one statement of its own after `notifyInterval`, so a node sends
  at most 10 notifying commits per second, however many jobs it writes, and an idle node sends
  none. A lost notification (the node dies right after its commit) only delays the job until the
  next interval poll.
- **Caller's transaction.** Jobs scheduled and events published on the caller's connection
  ([transactional scheduling](../jobs/README.md#transactional-scheduling)) notify inside the
  caller's transaction: the notification is sent on commit and not on rollback. That commit takes
  the lock mentioned above, so it is one notifying commit per transaction.
- **Connection pool.** A listening node holds one connection of its data source for its lifetime:
  size the pool one connection larger, and expect pool leak detectors to report it. The node
  borrows it on a thread of its own; if that fails, it warns and retries after the poll interval.
- **PgBouncer.** In transaction pooling mode, `LISTEN` does not work. A node checks after
  connecting that its own notification arrives and warns if it does not; then turn off
  `listenForDueJobs` on all nodes and lower the `pollInterval`, or connect the nodes directly.
- **All nodes alike.** Set `listenForDueJobs` the same on all nodes and clients (instances with
  `autoStartPolling(false)` notify but don't listen): the jobs a node made due without notifying
  reach the others only on their next interval poll.
- **Every node gets every notification.** A notification carries the job type only; nodes ignore
  types they don't handle, and all nodes handling the type poll (all but one claim nothing). Several
  bikesched schemas in one database share the channel `bikesched_jobs`, which only costs an extra
  poll.
- **Monitoring.** A full notification queue (8 GB by default) makes notifying commits fail;
  `select pg_notification_queue_usage()` shows how full it is. Notifications are short (one per
  type and batch) and consumed right away by the listening nodes.

## Statement timeout

Without a `statementTimeout`, a statement runs as long as the data source allows. Set it a good
deal above your slowest expected statement (e.g. 30 seconds), or configure the equivalent in the
data source or pool (`options=-c statement_timeout=30s` in the JDBC URL). The node applies it per
statement as JDBC query timeout, so no session setting is left on pooled connections; it also
covers statements of [transactional scheduling](../jobs/README.md#transactional-scheduling) on the
caller's connection.

A statement timeout does not help against a connection that hangs, e.g. after a database failover
without a TCP reset: the driver sends the cancel request on a new connection, but the answer would
arrive on the hanging one, so the statement keeps waiting and can block polling indefinitely. Set
the PostgreSQL driver's `socketTimeout` (in seconds, e.g. `socketTimeout=60` in the JDBC URL or as
a data source property) above the statement timeout and your slowest statement: a read that waits
longer fails and the pool replaces the connection. The connection a node holds to [listen for due
jobs](#due-jobs-start-right-away) is not affected: the driver waits for notifications with a
timeout of its own.

## JSON mapping

`bikesched-core` ships without a JSON library. Without a configured `jsonMapper`, bikesched only
accepts strings that already contain JSON (e.g. `"{\"to\": \"alice@example.com\"}"`) and passes them
through unchanged. To use records and classes as payloads, step results and event payloads, plug in
a JSON library.

### Jackson

The `bikesched-jackson` module (package `org.bikesched.jackson`) maps with Jackson 3 and brings
`bikesched-core` and `jackson-databind` along:

```java
Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .jsonMapper(new JacksonJsonMapper())   // Jackson's defaults: records, java.time types
        .build();
```

Pass a configured `ObjectMapper` to add modules or change features, e.g.
`new JacksonJsonMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())`
for Kotlin data classes. Mapping failures surface as Jackson's unchecked `JacksonException`.

### Other libraries

Implement `JsonMapper`'s two methods; they must be thread-safe and throw unchecked exceptions.
`null` never reaches the mapper:

```java
Gson gson = new Gson();
Bikesched.builder()
        .dataSource(dataSource)
        .jsonMapper(new JsonMapper() {
            public String toJson(Object value) {
                return gson.toJson(value);
            }

            public <T> T fromJson(String json, Class<T> type) {
                return gson.fromJson(json, type);
            }
        })
        .build();
```

### Payload design

We recommend objects (records or classes) as job and event payloads, rather than plain values,
arrays or collections:

```java
record ImportCustomers(List<CustomerId> customerIds) {}      // instead of a List<CustomerId>

bikesched.newJob(IMPORT_CUSTOMERS).payload(new ImportCustomers(ids)).schedule();
```

- **Evolution:** payloads are stored as JSON and read back later, possibly by a newer version of
  your code (jobs scheduled before a deploy, events kept for the retention period). A JSON object
  can gain properties without breaking payloads already stored, e.g. a new
  `record ImportCustomers(List<CustomerId> customerIds, boolean sendWelcomeMail)`; a bare list or
  string cannot.
- **Generic collections** like `List<CustomerId>` can't be described by a `Class` (the `JsonMapper`
  and `JobType` only take one), so wrapping them in an object is needed for typed payloads anyway.
- The same applies to step results, which are replayed after a retry or resume.

Make new properties optional (nullable, or a primitive whose zero value is a sensible default), so
payloads stored by an older version still map. With Jackson 3's defaults (as in `bikesched-jackson`), a record component missing from
the JSON becomes `null` (or `false`/`0`), and properties the class doesn't know are ignored. So
during a rolling deploy, old and new code can read each other's payloads.

## Logging

bikesched logs through the SLF4J API under the logger names `org.bikesched.*`; bring your own
binding. On its own threads it fills the logging context with the keys from `BikeschedLogContext`:
`bikesched.nodeId`, `bikesched.jobId`, `bikesched.jobType` and `bikesched.stepName`, each only where
it applies. With Logback:

```xml
<pattern>%d %-5level [%thread] node=%X{bikesched.nodeId} job=%X{bikesched.jobId} %logger - %msg%n</pattern>
```

When a node starts polling, the registration message ends with the library version
(`… (bikesched 0.1.0)`). The same version is available in code as `Bikesched.version()` and in the
jar manifest as `Implementation-Version`; the schema is migrated to the state of that version.

On startup bikesched logs the database URL (without credentials) at `DEBUG` as
`Database: jdbc:postgresql://…`, which IDE plugins such as the
[Testcontainers Port Updater](https://github.com/yusufugurozbek/testcontainers-port-updater) pick up.
