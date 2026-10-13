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

## Testing

### Test support

A first slice is implemented as `bikesched-test` (a test node factory and `JobAwait` wait helpers
over a real database, see [the spec](docs/specs/bikesched-test.md) and
[Testing](docs/testing.md)). Still open:

- Unit level, no database: a fake `Job` that runs a handler in the test thread, records steps,
  their results and compensations, returns prepared events for `waitForEvent` and child job results
  for `waitForJob`, and skips sleeps and polls; asserts the outcome (completed, failed, cancelled,
  suspended at step …).
- A JUnit 5 extension managing the database (Testcontainers, schema reset between tests) and the
  node's lifecycle, a manual poll entry point (`runUntilIdle()`, next to `autoStartPolling`) instead
  of the real poller, and `fastForward(jobId)` to make a sleeping or polling job due now (the
  database decides with `now()`, so a `Clock` alone cannot skip time).
- Open: whether the unit-level fake is worth its drift from the real `Job` (it reimplements the
  replay semantics), or whether a fast integration level is enough.
- Outcome: handler tests that run in milliseconds and read like the handler's flow.

### Load and failure tests

A first set runs on demand in `bikesched-stress-tests` (database restarts and outages, paused
nodes, hanging connections; benchmarks for a backlog, a steady load and jobs with child jobs, event
waits, sleeps and timeouts; see [its README](bikesched-stress-tests/README.md) and
[ADR 0042](docs/decisions/0042-load-and-failure-tests-on-demand.md)). Still open:

- More failures: a failover to a promoted replica, a database that turns read-only or runs out of
  disk, a pool exhausted by the application's own work.
- More load: the job history enabled, compensations and failure handlers, nodes and database on
  separate machines; a manually triggered workflow that runs the tests and keeps the numbers of
  each release for comparison.
- Outcome: every release states which failures it survives and how fast it is.

### Statement timeout on hanging connections

A `statementTimeout` does not end a statement on a connection that hangs without a TCP reset: the
driver sends the cancel on a new connection, the answer would arrive on the hanging one. The poller
then waits forever while the heartbeat (on fresh connections) keeps the node alive, so nothing
takes over its job types. Today the docs ask for the driver's `socketTimeout`
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

### Nullness annotations beyond the core API

The exported packages of `bikesched-core` are `@NullMarked` with JSpecify
([ADR 0041](docs/decisions/0041-jspecify-nullness-annotations.md)); the internal packages, the other
Java modules (`bikesched-jackson`, `bikesched-annotations`, `bikesched-cron`,
`bikesched-micrometer`, `bikesched-spring-boot-starter`, `bikesched-admin`, `bikesched-test`) and
the build are not covered yet.

- Mark the internal packages and the other modules' packages with `@NullMarked`, adding
  `@Nullable` to fields, parameters and returns that may be `null`.
- Enforce the annotations in the build, so a missing `@Nullable` fails the compile instead of
  waiting for review. NullAway in JSpecify mode passes on the annotated API packages today.
- To decide between:
  - NullAway (Error Prone plugin) on every Java module.
  - Keep it documentation-only and check with NullAway now and then.
- Outcome: nullness checked across the code base, not only declared on the API.

### Fixed-delay recurrences

`Recurrence.every(Duration)` runs at a fixed rate; the other recurrences compute the next run from
the previous occurrence's scheduled time. Neither waits for a run to finish: a sync job taking 10
of its 15 minutes leaves only 5 minutes to the next run. A fixed delay (Spring's `fixedDelay`)
starts the interval when the previous occurrence ended.

- Core: e.g. `Recurrence.everyAfterCompletion(Duration)`, described as `every PT15M after
  completion`. `RecurringJob.nextRun(ended, now)` already receives the end time (`now`); the
  recurrence needs a way to ask for it instead of the planned or scheduled time, e.g. an enum
  replacing `isFixedRate()` (`SCHEDULED`, `PLANNED`, `COMPLETED`).
- Annotations: a schedule format for it, or a `fixedDelay` attribute next to `schedule`; ISO
  durations stay fixed rate.
- Not a boolean `fixedRate` flag on `@JobHandler`: computing from the scheduled time is neither a
  fixed rate nor a fixed delay, and the flag would do nothing for wall-clock schedules.
- Outcome: interval jobs that never overlap their own next run and keep a gap after long runs.

### Handler versions

Jobs live across deploys and replay with the handler code deployed by then.
[Changing handlers](docs/changing-handlers.md) lists the safe and unsafe changes and works around
the unsafe ones with a version property in the payload, a new job type or two deploys. Nothing in
the API tells old jobs from new ones, and nothing stops an old node from running a job created for
new code during a rolling deploy.

- Handler versions: `registerJobHandler(type, version, handler)` stores the version on each new
  job (`handler_version` column), `job.handlerVersion()` lets the handler branch for old jobs, and
  a node claims only versions it supports (like the type filter during rolling deploys, so an old
  node does not run a job created for new code).
- Alternatively per change: `job.isPatched("change-id")`, recorded as a step on first evaluation,
  so jobs that ran past that point before the change keep the old path.
- To decide between: per-handler versions, per-change markers, or both.
- Open: what happens to jobs whose version no node supports any more (stuck `SCHEDULED`, an
  incident?).
- Outcome: deploys that change a handler without breaking jobs already in flight, without
  encoding versions in payloads or type names; postponed until a real case needs more than the
  workarounds.

### Reference

Allow jobs and events to store an external reference string (max 200 chars, like job types and
event names) when created, e.g. an id from an external system (order id, payment id, webhook
delivery id), so it can be looked up or shown without decoding the payload.

- Optional `reference(String)` on the job builder (also the transactional one) and the event
  builder; `char_length` CHECK + `ValidationUtil`, like other 200-character names.
- Column `reference` on `bikesched_jobs` and `bikesched_events`, nullable, not unique (unlike the
  [idempotency key](docs/jobs.md#idempotency-keys), this is a display/lookup aid, not a dedup
  key) — multiple jobs/events can share one external reference (e.g. several jobs for the same
  order).
- Needs an index to actually look up by it (e.g. `(type, reference)` on jobs); postponed until
  there is a concrete lookup need.
- Open: whether it is mutable after creation, and whether it shows up in job history.

### Stable step id for external callbacks

`bikesched_steps` is keyed by `(job_id, name)`; nothing about a step is a single opaque,
externally-shareable handle. Add `step.id()`, a `UUID` available on every step regardless of
kind, so application code can hand a stable token to an external system (e.g. embedded in a
callback URL) and have it call back with just that one value.

- `bikesched_steps.id uuid not null`, unique-constrained; generated by the library (like
  `bikesched_jobs.id`) on first insert, supplied only in the insert values — never written by the
  `on conflict (job_id, name) do update` clause, so it stays stable across retries of the step.
- Keep `primary key (job_id, name)`: all internal addressing (`StepStore`, `EventStore`,
  `HistoryStore`) is unaffected.
- Needs a new lookup path keyed on `id` alone (not through the job first) for whatever API
  receives the external callback.
- Open: the callback-receiving API shape (new method on `Bikesched`? a step kind addition?) —
  deferred until there's a concrete use case.

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

## Admin UI

### Publish events from the admin UI

The admin UI currently shows jobs and events but is read-only. It should support publishing new
events to test and manually trigger handlers that wait for events, without needing code or direct
database access.

- Admin UI form to pick an event name/type, enter a key and optional JSON payload, and publish it.
- Backend JSON parsing and validation of the payload (reuses existing `JsonMapper`).
- Endpoint secured by the existing `AdminAccess` SPI.
- Outcome: operators can publish events in development and testing without code changes.

## Website

### Custom domain for bikesched.org

`bikesched-website` is generated with Hugo and deployed to static.app on every push to `main`
touching `docs/**`, `README.md` or `bikesched-website/**` (see
[spec](docs/specs/website-project-site.md),
[ADR 0035](docs/decisions/0035-website-hosted-on-static-app.md)). The site currently serves from
the default `https://bikesched.staticdomains.app` subdomain; the already reserved domain
`bikesched.org` still needs to be configured on static.app and pointed at it by DNS, with HTTPS
enforced, before `hugo.toml`'s `baseURL` (already `https://bikesched.org/`) matches reality.
