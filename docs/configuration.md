# Configuration

A node is configured with `Bikesched.builder()`; only the data source is required.

## Builder options

| Builder option | Default | Description |
|---|---|---|
| `dataSource(DataSource)` | required | PostgreSQL data source; tables are created in its default schema |
| `nodeName(String)` | host name | unique node name; a dead node's name is taken over |
| `maxConcurrentJobs(int)` | 10 | jobs executed at the same time on this node |
| `autoStartPolling(boolean)` | `true` | with `false`, call `startPolling()` once all handlers are registered; until then the node is not registered, executes no jobs and sends no heartbeats, e.g. for an instance that only schedules jobs. `build()` then does not access the database, so it works while the database is down: the schema is migrated on first use (`startPolling()`, scheduling a job, publishing an event), and retried on the next one if that fails |
| `defaultJobTimeout(Duration)` | none | timeout for jobs scheduled without one |
| `retryBackoff(RetryBackoff)` | `RetryBackoff.none()` | delay before retrying a failed attempt, e.g. `RetryBackoff.fixed(Duration.ofSeconds(30))` or `RetryBackoff.exponential(Duration.ofSeconds(5), Duration.ofMinutes(10))`; a handler's `job.fail().retryAfter(…)` takes precedence |
| `jsonMapper(JsonMapper)` | JSON strings only | maps payloads, step results and event payloads to and from JSON, see [JSON mapping](#json-mapping) |
| `eventRetention(Duration)` | 7 days | how long published events are kept for steps that start waiting later |
| `eventCleanUpInterval(Duration)` | 1 hour | how often the node deletes events older than the retention |
| `nodeCleanUpInterval(Duration)` | 15 seconds | how often the node releases the running jobs of dead nodes and removes dead nodes; also the retry delay of the one-time check for missing occurrences of its recurring jobs |
| `defaultEventTimeout(Duration)` | none | maximum wait for events without a `.timeout(...)` of their own; afterwards the job fails with an `EventTimeoutException` |

## Nodes

Nodes send a heartbeat every 5 seconds and poll for due jobs every second, claiming at most 10
jobs per poll and never more than they have free slots. A node without heartbeat for 30 seconds is
considered dead: on their next node clean-up, other nodes release its running jobs for retry, and
remove the node after another 30 seconds. The heartbeat itself only updates the node's row; the
clean-ups run on their own thread and intervals, so they never delay it.

`bikesched.node()` describes the node (`name()`, `id()` once registered, `isPolling()`).
`close()` stops polling and the heartbeat, waits up to 10 seconds for running jobs, puts jobs
that are still running back to `SCHEDULED` for other nodes, and deregisters the node.

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

## Logging

bikesched logs through the SLF4J API under the logger names `org.bikesched.*`; bring your own
binding. On its own threads it fills the logging context with the keys from `BikeschedLogContext`:
`bikesched.nodeId`, `bikesched.jobId`, `bikesched.jobType` and `bikesched.stepName`, each only where
it applies. With Logback:

```xml
<pattern>%d %-5level [%thread] node=%X{bikesched.nodeId} job=%X{bikesched.jobId} %logger - %msg%n</pattern>
```

On startup bikesched logs the database URL (without credentials) at `DEBUG` as
`Database: jdbc:postgresql://…`, which IDE plugins such as the
[Testcontainers Port Updater](https://github.com/yusufugurozbek/testcontainers-port-updater) pick up.

---

Next: [Kotlin](kotlin.md) · Previous: [Events](events.md) · [Documentation index](index.md)
