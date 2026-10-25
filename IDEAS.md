# Ideas

Possible future improvements, not planned yet. Each entry describes the problem and a sketched
solution; remove an entry once it is implemented (or decided against).

## Reliability

### Outage incidents with a local buffer

A node that cannot reach the database only logs it. It could record the outage as an incident in a
local buffer and write it to the database once connected again (insert with its UUID, `on
conflict do nothing`). Job failures need no buffer: they cannot happen while the database is down
and open their incident later, in the same transaction as before ([spec](docs/specs/incidents.md)).

- Buffer: an append-only JSON-lines file, no new core dependency; if that is not enough, an
  embedded store in an optional module: [MVStore](https://www.h2database.com/html/mvstore.html),
  [Xodus](https://github.com/JetBrains/xodus), [SQLite](https://github.com/xerial/sqlite-jdbc),
  [RocksDB](https://rocksdb.org) or [LMDB](https://github.com/lmdbjava/lmdbjava).
- Needs incidents without a job (nullable `job_id`, a kind, the node name).

## Performance

### Fewer round trips per step

A step reads its row, starts it and completes it, each with its own connection checkout (more with
the job history). Combining the read and the start into one statement would roughly halve that.

## Operations

### Trace propagation

A job runs later, often on another node, so its work shows up in no trace: the request that
scheduled it and the job's attempts are not connected. A dedicated module (e.g.
`bikesched-opentelemetry`) could carry the trace context from the scheduling code into the job.

- Core: a small dependency-free SPI, e.g. a propagator that captures context when a job is
  scheduled or an event is published (a `Map<String, String>`) and wraps every attempt with it;
  stored in a new nullable column (e.g. `context jsonb` on `bikesched_jobs`), also usable for
  other propagated values (tenant id, correlation id into the MDC).
- Module: captures the W3C `traceparent`/`tracestate` of the current OpenTelemetry context,
  creates a span per attempt and per step, and propagates it to child jobs and published events.
- Open: the attempt as a child of the scheduling span or only linked to it (a job running days
  later or retried many times makes a long, odd parent trace); OpenTelemetry API directly or
  Micrometer Observation (what Spring Boot and `bikesched-micrometer` use); whether a resumed
  job (after a wait, sleep or poll) continues the same trace.
- Outcome: one trace from the request through every attempt and step of the jobs it started.

### Reporting events published in the caller's transaction

`EventListener` does not report events published with `connection(…)`: plain JDBC gives bikesched
no commit callback, and reporting before the commit could count rolled-back events (see ADR 0030).
The Spring starter could register a `TransactionSynchronization` and report after the commit when
the connection belongs to a Spring-managed transaction.

### Pausing jobs

An operator can cancel a job or one occurrence of a recurring job, and remove a recurring job that
no node registers any more, but cannot hold work back for a while and let it continue later, e.g.
during an incident with a downstream system. Removing a recurring job is undone by the next node
start, and cancelling loses the job.

- Recurring jobs: a paused flag per name in a small table (it must survive restarts and
  re-registration); the pending occurrence stays, is not claimed while paused, and resuming makes
  it due (missed runs skipped as usual).
- Running and scheduled one-off jobs: `Bikesched.pause(jobId)` marks the job like a cancel request
  (`pause_requested_at`); a scheduled or waiting job is not claimed, a running one is suspended at
  its next safe point (the end of the current attempt or the next step boundary, like a
  postponement) and keeps its steps; `resume(jobId)` makes it due again.
- Preferred: a job state of its own rather than a flag next to the state, so a paused job is
  visible in every query and the admin UI; `PAUSED` rather than `SUSPENDED`, which internally
  already means a job ending its attempt at a wait, sleep or poll (`JobSuspended`).
- Admin UI: pause and resume buttons on a job and on a recurring job.
- Open: whether event waits and timeouts keep running while paused, and whether a name-wide pause
  for one-off jobs (all jobs with a name) is needed too.
- Outcome: operators stop and continue work without losing jobs or their progress.

## Testing

### Test support

A first slice is implemented as `bikesched-test` (a test node factory, `JobAwait` wait helpers and
time skipping over a real database, see [the spec](docs/specs/bikesched-test.md) and
[Testing](docs/integrations/testing.md)). Still open:

- Unit level, no database: a fake `Job` that runs a handler in the test thread, records steps,
  their results and compensations, returns prepared events for `waitForEvent` and child job results
  for `waitForJob`, and skips sleeps and polls; asserts the outcome (completed, failed, cancelled,
  suspended at step …).
- A JUnit 5 extension managing the database (Testcontainers, schema reset between tests) and the
  node's lifecycle, and a manual poll entry point (`runUntilIdle()`, next to `autoStartPolling`)
  instead of the real poller.
- Open: whether the unit-level fake is worth its drift from the real `Job` (it reimplements the
  replay semantics), or whether a fast integration level is enough.
- Outcome: handler tests that run in milliseconds and read like the handler's flow.

### Load and failure tests

A first set runs on demand in `bikesched-stress-tests` (database restarts and outages with jobs
using child jobs, event waits, sleeps and timeouts, hanging connections, the throughput of a
backlog; see [its README](bikesched-stress-tests/README.md) and
[ADR 0050](docs/decisions/0050-load-and-failure-tests-on-demand.md)). Still open:

- More failures: a failover to a promoted replica, a database that turns read-only or runs out of
  disk, a pool exhausted by the application's own work.
- More load: start latency under a steady load, the job history enabled, nodes and database on
  separate machines; a manually triggered workflow that runs the tests and keeps the numbers of
  each release for comparison.
- Outcome: every release states which failures it survives and how fast it is.

### Statement timeout on hanging connections

A `statementTimeout` does not end a statement on a connection that hangs without a TCP reset: the
driver sends the cancel on a new connection, the answer would arrive on the hanging one. The poller
then waits forever while the heartbeat (on fresh connections) keeps the node alive, so nothing
takes over its jobs. Today the docs ask for the driver's `socketTimeout`
(`HangingConnectionTest` covers it).

- bikesched sets `Connection.setNetworkTimeout(…)` on every connection it borrows, a margin above
  the statement timeout, and resets it before returning the connection (also on a caller's
  connection).
- To decide between:
  - Derive the network timeout from `statementTimeout`.
  - A separate builder option `networkTimeout(Duration)`.
  - Keep it a data source setting, documented only.
- Outcome: a node never hangs on a broken connection, whatever the data source sets.

## API

### Nullness checks beyond the core module

All packages of `bikesched-core` are `@NullMarked` and checked with NullAway in the build
([ADR 0048](docs/decisions/0048-nullaway-in-the-core-build.md)); the other Java modules
(`bikesched-jackson`, `bikesched-annotations`, `bikesched-cron`, `bikesched-micrometer`,
`bikesched-spring-boot-starter`, `bikesched-admin`, `bikesched-test`) are not covered yet.

- Mark their packages with `@NullMarked`, adding `@Nullable` where `null` occurs.
- Apply the `bikesched.errorprone` convention plugin to each, fixing its NullAway and Error Prone
  findings; drop null checks the types then exclude.
- Outcome: nullness checked across the code base, not only in core.

### Handler versions

Jobs live across deploys and replay with the handler code deployed by then.
[Changing handlers](docs/jobs/changing-handlers.md) lists the safe and unsafe changes and works
around the unsafe ones with a version property in the payload, a new job name or two deploys.
Nothing in the API tells old jobs from new ones, and nothing stops an old node from running a job
created for new code during a rolling deploy.

- Handler versions: `registerJobHandler(name, version, handler)` stores the version on each new
  job (`handler_version` column), `job.handlerVersion()` lets the handler branch for old jobs, and
  a node claims only versions it supports (like the name filter during rolling deploys, so an old
  node does not run a job created for new code).
- Alternatively per change: `job.isPatched("change-id")`, recorded as a step on first evaluation,
  so jobs that ran past that point before the change keep the old path.
- To decide between: per-handler versions, per-change markers, or both.
- Open: what happens to jobs whose version no node supports any more (stuck `SCHEDULED`, an
  incident?).
- Outcome: deploys that change a handler without breaking jobs already in flight, without
  encoding versions in payloads or job names; postponed until a real case needs more than the
  workarounds.

### Stable step id for external callbacks

Every step has an id (`bikesched_steps.id`, a UUIDv7 kept across its attempts, see
[ADR 0057](docs/decisions/0057-steps-keyed-by-a-time-ordered-id.md)), but only the store and the
admin see it. Exposing it as `step.id()` would let application code hand a stable token to an
external system (e.g. embedded in a callback URL) and have it call back with just that one value.

- `Step` (the record handed to step handlers) gets the id; the replay already reads it with the
  step.
- Needs a lookup keyed on the id alone (not through the job first) for whatever API receives the
  external callback.
- Open: the callback-receiving API shape (new method on `Bikesched`? a new step type?),
  deferred until there's a concrete use case.
- Outcome: an external system resumes a job's step with nothing but the token it was given.

## Examples

### Koog agent example

[Koog](https://github.com/JetBrains/koog) is a JVM (Java/Kotlin) framework for building AI agents;
an agent's tool calls are a natural fit for bikesched jobs — durable, retried and observable
instead of in-memory. An example combining the two would show an agent whose tool calls schedule
bikesched jobs (and, for long-running tools, use a poll step to wait for the result) in the
Kotlin examples.

- A new package in `bikesched-examples/kotlin` (or a new `bikesched-examples/kotlin` submodule
  next to `java`/`spring`, mirroring the existing examples' Testcontainers-backed test), using
  bikesched-kotlin; pulls in `ai.koog:koog-agents-jvm` as an example-only dependency (never core).
- Open: which tool call to model (something with a visible external effect, e.g. a notification
  or a fake payment, to make the durability payoff obvious) and whether Koog's own tool-calling
  abstraction needs any adapter, or jobs are just scheduled from a plain Kotlin tool function.
