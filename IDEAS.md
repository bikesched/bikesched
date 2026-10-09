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

### Finding jobs

`findJob(id)` needs the job id; there is no way to ask e.g. which jobs of a type failed without
SQL. A small query API (`findJobs(type, state, limit)`, newest first) would be the basis for an
admin UI, alerts and bulk `retry(...)`.

- Needs an index fitting the query (e.g. `(type, state, updated_at)`), so it costs writes on
  every state change; postponed until it is needed. Until then: SQL on `bikesched_jobs`.

### Hook for permanently failed jobs

bikesched has no hook for a job ending `FAILED` or `COMPENSATION_FAILED`, e.g. to alert or move it to a dead-letter store. The
handler cannot do this reliably itself: it does not run after a timeout, an abandoned attempt or a
crash of its node.

- `JobListener.onAttemptEnded` reports `FAILED` and `COMPENSATION_FAILED` synchronously after the
  commit, by the node recording it (lost if that node dies right after the commit); enough for
  alerts via [Metrics](docs/metrics.md).
- Planned as incidents: see [the spec](docs/specs/incidents.md).

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

Testing a handler today means a real database plus Awaitility, and sleeps, event waits and polls
make a test wait as long as the job would. A `bikesched-test` module could make handlers testable
without timing tricks, on two levels:

- Unit level, no database: a fake `Job` that runs a handler in the test thread, records steps,
  their results and compensations, returns prepared events for `waitForEvent` and child job results
  for `waitForJob`, and skips sleeps and polls; asserts the outcome (completed, failed, cancelled,
  suspended at step …).
- Integration level, real PostgreSQL: a JUnit 5 extension with a Testcontainers database and a
  node per test (schema reset between tests); `runUntilIdle()` polls and runs due jobs in the
  test thread instead of the poller (needs a manual poll entry point next to `autoStartPolling`),
  `awaitJob(id, state)` instead of hand-written Awaitility, and `fastForward(jobId)` to make a
  sleeping or polling job due now (the database decides with `now()`, so a `Clock` alone cannot
  skip time).
- Open: whether the unit-level fake is worth its drift from the real `Job` (it reimplements the
  replay semantics), or whether a fast integration level is enough.
- Outcome: handler tests that run in milliseconds and read like the handler's flow.

### Load and failure tests

- Benchmarks for throughput and latency per node.
- Failure tests: database restart in the middle of a job (would expose the outcome-recording issue
  above), a node pausing longer than the node timeout, a hanging connection.

## API

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

### Changing handlers of running jobs

Jobs live across deploys: a job sleeping for days, waiting for an event or retried after a failure
resumes with the new handler code and replays it from the top. Steps are addressed by name, so
reordering them is safe, but other changes are not: a renamed step runs its side effect again, a
step whose result type changed fails to read its stored result, and changed code outside steps
may take another path than the first attempt did. Nothing in the docs or the API helps with this.

- Docs first: a page on evolving handlers (safe and unsafe changes, keeping result types
  compatible, adding a step before completed ones, removing steps, draining old jobs).
- Handler versions: `registerJobHandler(type, version, handler)` stores the version on each new
  job (`handler_version` column), `job.handlerVersion()` lets the handler branch for old jobs, and
  a node claims only versions it supports (like the type filter during rolling deploys, so an old
  node does not run a job created for new code).
- Alternatively per change: `job.isPatched("change-id")`, recorded as a step on first evaluation,
  so jobs that ran past that point before the change keep the old path.
- Open: per-handler versions vs. per-change markers (or both); what happens to jobs whose version
  no node supports any more (stuck `SCHEDULED`, an incident?).
- Outcome: deploys that change a handler without breaking jobs already in flight.

### Idempotency keys for jobs and events

A caller that retries (a webhook delivered twice, a request retried after a timeout) schedules the
same job or publishes the same event twice. Callers would have to deduplicate themselves before
calling bikesched.

- Optional `idempotencyKey(String)` on the job builder (also the transactional one) and the event
  builder; at most 500 characters like event keys (`char_length` CHECK + `ValidationUtil`).
- Jobs: column `idempotency_key` with a partial unique index on (`type`, `idempotency_key`)
  over the states that are not ended (`SCHEDULED`, `RUNNING`, `WAITING`, `COMPENSATING`), like
  `bikesched_jobs_recurring_idx`. `insert … on conflict do nothing`, then `schedule()` returns the
  id of the existing job instead of creating one; once the job has ended, the key is free again.
  Open: unique per job type (proposed) or globally; whether a different payload for the same key
  should fail instead of being ignored.
- Events: they have no completion, only an expiry (`expires_at`), and a partial index cannot use
  `now()`. Check for an unexpired event with the same name and idempotency key inside the advisory
  lock `publish` already takes on name and key, and skip the publish if one exists.
- With job history: record the skipped duplicate ("duplicate of idempotency key …") on the existing
  job.
- Outcome: retried callers create each job and event once, without their own deduplication table.

### Reference

Allow jobs and events to store an external reference string (max 200 chars, like job types and
event names) when created, e.g. an id from an external system (order id, payment id, webhook
delivery id), so it can be looked up or shown without decoding the payload.

- Optional `reference(String)` on the job builder (also the transactional one) and the event
  builder; `char_length` CHECK + `ValidationUtil`, like other 200-character names.
- Column `reference` on `bikesched_jobs` and `bikesched_events`, nullable, not unique (unlike the
  idempotency key above, this is a display/lookup aid, not a dedup key) — multiple jobs/events can
  share one external reference (e.g. several jobs for the same order).
- Needs an index to actually look up by it (e.g. `(type, reference)` on jobs); postponed until
  there is a concrete lookup need, similar to [Finding jobs](#finding-jobs).
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

### Examples in submodules by setup

`bikesched-examples` is a single module with one package per scenario, so every example shares
one classpath: the plain Java examples get Spring Boot and JPA (only needed by the Spring
example), and all share one `application.properties` and one `logback.xml`. A reader cannot tell
which dependencies the plain examples need and which only Spring needs.

- Split it into submodules under `bikesched-examples/` by setup, not one per example:
  - plain Java (e.g. `bikesched-examples:java`): simple, taxi, annotation, event, poll, outcome,
    publish and cinema, with bikesched-jackson and bikesched-annotations only, keeping a `run…`
    task per example;
  - Spring Boot (e.g. `bikesched-examples:spring`): a regular Spring Boot application with the
    starter, JPA, the Spring Boot Gradle plugin (`bootRun`) and its own `application.properties`.
- Shared test code (`util.Database`, the Testcontainers setup) moves into a small shared
  submodule or test fixtures, so both modules use one container setup.
- Update the examples README, the root README, `AGENTS.md` and the run commands in `docs/`
  (`:bikesched-examples:runSimple` becomes e.g. `:bikesched-examples:java:runSimple`).
- Outcome: the plain examples show the minimal dependencies, and the Spring example is a Spring
  Boot application as users would build it.

## Website

### Project site on bikesched.org

Deploy the `bikesched-website` Hugo site (generator, theme and content reuse decided, see
[spec](docs/specs/website-project-site.md)) as a GitHub Pages site under the already reserved
domain `bikesched.org`, built and published by a GitHub Actions workflow.

- Workflow: `./gradlew :bikesched-website:hugoBuild` on push to `main` (and PRs, build only),
  publishing `bikesched-website/build/hugo/publish/` with `actions/deploy-pages`.
- Custom domain: a `CNAME` file in the published output (`baseURL` already set to
  `https://bikesched.org/`), DNS records for `bikesched.org` pointing to GitHub Pages, the domain
  set in the repository's Pages settings, HTTPS enforced.
- GitHub Pages for a private repository needs a paid plan (GitHub Pro); on the free plan the
  repository has to be public first.
