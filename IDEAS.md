# Ideas

Possible future improvements, not planned yet. Each entry describes the problem and a sketched
solution; remove an entry once it is implemented (or decided against).

## Reliability

### Retry recording a job's outcome

If recording an attempt's outcome fails (e.g. a database blip right after the handler returned),
`JobProcessor` only logs a warning: the job stays `RUNNING`, locked by a node that is alive and
heartbeating, so node clean-up never releases it. It is stuck until that node restarts. Applies to
completion, failure, cancellation, suspension and compensation outcomes.

- Retry recording the outcome a few times with a short backoff.
- Periodically release this node's own `RUNNING` jobs that are no longer running in memory, after
  a grace period for freshly claimed jobs (so a job the poller just claimed is not released).

### Poller watchdog

With no or a long statement timeout, a hanging connection blocks the single poller thread while the
heartbeat (own thread) keeps the node looking alive. A watchdog could log and recover when a poll
takes far longer than the poll interval.

## Performance

### Poll again right away when more jobs are due

Each poll claims at most `min(free slots, 10)` jobs and then waits the full `pollInterval`, even if
more jobs are due: about 10 jobs per second per node with the defaults, regardless of
`maxConcurrentJobs`. Poll again immediately when a poll filled its batch (and slots are free).

### LISTEN/NOTIFY

Wake pollers with PostgreSQL `LISTEN/NOTIFY` when a job is scheduled (or becomes due, e.g. resumed
by an event), instead of waiting for the next poll. Lowers latency and allows a longer poll
interval, so less idle polling. Polling stays as the fallback (notifications are lost while a node
is disconnected). Needs a dedicated connection per node that stays open, which interacts with
connection pools.

### Fewer round trips per step

A step reads its row, starts it and completes it, each with its own connection checkout (more with
the job history). Combining the read and the start into one statement would roughly halve that.

## Operations

### Connection pool guidance

Every store call borrows its own connection. With `PGSimpleDataSource`, used by the examples and
docs, that is a new TCP connection and authentication per statement, several per step. Document
using a pool (e.g. HikariCP) and how to size it: job slots plus poller, heartbeat and clean-ups.

### Finding jobs

`findJob(id)` needs the job id; there is no way to ask e.g. which jobs of a type failed without
SQL. A small query API (`findJobs(type, state, limit)`, newest first) would be the basis for an
admin UI, alerts and bulk `retry(...)`.

- Needs an index fitting the query (e.g. `(job_type, state, updated_at)`), so it costs writes on
  every state change; postponed until it is needed. Until then: SQL on `bikesched_jobs`.

### Hook for permanently failed jobs

Quartz has `JobListener`, db-scheduler a callback after the last retry. bikesched has no hook for a
job ending `FAILED` or `COMPENSATION_FAILED`, e.g. to alert or move it to a dead-letter store. The
handler cannot do this reliably itself: it does not run after a timeout, an abandoned attempt or a
crash of its node.

- Option: a listener SPI on the builder (`JobListener.onFailed(JobInfo)`), called after the commit
  by the node recording the failure (at most once; lost if that node dies right after the commit).
- Option: only through [Metrics](#metrics) and alerting on them.
- Open: synchronous or on its own thread, other events (succeeded, cancelled), and how it relates to
  the job history.

### Metrics

Nothing reports due jobs, the delay between due and claimed, attempt durations, failure rates or
stuck `RUNNING` jobs. A small listener SPI in core, with optional Micrometer support in its own
module, would make the problems above visible in production.

## Testing

### Load and failure tests

- Benchmarks for throughput and latency per node.
- Failure tests: database restart in the middle of a job (would expose the outcome-recording issue
  above), a node pausing longer than the node timeout, a hanging connection.

## API

### Waiting for a typed event without `receive()`

`job.waitForEvent(step, eventType, key)` returns a `TypedEventWait<T>` that still has to be
completed with `receive()`. For the common case, waiting should directly return the event's payload,
typed by the event type, as the result of the wait step:

```java
Payment payment = job.awaitEvent("await-payment", PAYMENT_RECEIVED, orderId);   // no receive()
```

- Needs its own method name (e.g. `awaitEvent`): an overload of `waitForEvent` with the same
  parameters cannot differ in its return type.
- Optionally an overload with a timeout (`Duration`), returning `null` or throwing
  `EventTimeoutException` when it passes.
- Stored like today, as the event step's `result`, so replays return the same value.
- `waitForEvent(...).timeout(...).receive()` stays for waits with a timeout handler.

### Steps inside steps

A step handler can currently call `job.step(...)` (or wait for an event) itself; nothing prevents
it. The semantics are unclear: the inner step completes durably even if the outer step then
fails, a retry runs the outer step again but skips the inner one, and a wait inside a step would
suspend the job while the outer step is still `RUNNING`.

- Either allow nesting and document it (inner steps are independent durable steps), or
- guard it with a flag on the job that is set while a step runs, so that creating a step, a step
  builder or an event wait inside a step throws an `IllegalStateException`.

### Fallbacks in event timeout handlers

Verify whether timeout handlers work for fallbacks, e.g.: a Stripe webhook has not arrived yet, so
fetch the payment by its id from Stripe, or if it is not available yet either, try again in 5
minutes.

- The timeout handler runs again on every replay after the deadline, so a fetch inside it is
  repeated; it would need to be a step, inside the handler (see
  [Steps inside steps](#steps-inside-steps)) or after the wait.
- "Try again in 5 minutes": the wait step stays `TIMED_OUT`, so waiting again needs a new step
  name, or the job fails the attempt with `job.fail().retryAfter(Duration.ofMinutes(5))`, which uses
  up an attempt and runs the fallback again.
- A webhook arriving after the timeout is not delivered to the timed-out step; it is kept for the
  event retention period, so a later wait (new step name) still receives it.
- Outcome: a documented pattern with a test, or API support (e.g. a timeout handler that can wait
  again).

## Modules

### bikesched-cron

A separate module `bikesched-cron` with cron expressions as `Recurrence`, e.g.
`CronRecurrence.of("0 3 * * MON-FRI", ZoneId.of("Europe/Berlin"))`. The parsing comes from an
external library, so core stays free of the dependency and of a cron dialect of its own.

- Library: [cron-utils](https://github.com/jmrozanec/cron-utils) (Unix, Quartz and Spring
  dialects, `ExecutionTime.nextExecution`). Before choosing it, check its maintenance, its
  transitive dependencies and JPMS support; alternatives are Spring's `CronExpression` (pulls in
  `spring-context`) or a small parser of our own.
- `next(previous)` calculates the next execution after `previous` in the given zone. The
  description is the normalized expression plus the zone (e.g. `cron 0 3 * * MON-FRI
  Europe/Berlin`), so changing the expression moves the pending occurrence. Not fixed rate: cron
  snaps to the wall clock.
- Choose the dialect explicitly (Unix by default, Quartz with seconds as an option), and reject
  invalid expressions and expressions without a next execution when the recurrence is created.
- Daylight saving time: document how skipped and repeated local times behave, and test them.
- Kotlin: probably no extra API, the recurrence is passed to `createRecurringJob` as is.

### bikesched-spring

A Spring Boot integration `bikesched-spring` (auto-configuration plus starter) that creates the
`Bikesched` node from the application context and registers job handlers found as beans, e.g.:

```java
@Component
@BikeschedJob("send-invoice")
class SendInvoiceHandler implements TypedJobHandler<Invoice> {
  public void handle(Job job, Invoice invoice) { … }
}
```

- Discovery: beans implementing `JobTypeHandler` are registered as they are (they know their
  type); beans annotated with a type name (and a handler interface) as well. Possibly also
  annotated methods (`@BikeschedJob("…") void sendInvoice(Job job, Invoice invoice)`), like
  `@Scheduled`/`@EventListener`. The annotation needs a name that does not clash with
  `org.bikesched.job.Job` (e.g. `@BikeschedJob` or `@JobHandler`).
- Recurring jobs declared on the handler (`@BikeschedJob(value = "…", cron = "…")`, with
  [bikesched-cron](#bikesched-cron)) and registered on startup.
- Auto-configuration: the context's `DataSource`, the Jackson `ObjectMapper` (via
  bikesched-jackson) as `JsonMapper`, the builder options as `bikesched.*` properties, and a
  `BikeschedBuilderCustomizer` bean for everything else. Build the node with
  `autoStartPolling(false)`, register the handlers, then `startPolling()` with the context
  (`SmartLifecycle`) and close it on shutdown; polling can be turned off per application (e.g.
  `bikesched.polling.enabled=false` for a web app that only schedules jobs).
- Transactions: scheduling jobs and publishing events inside a Spring transaction should use its
  connection (`DataSourceUtils.getConnection`), so they commit or roll back with it.
- Optional later: Actuator health (node heartbeat) and metrics via Micrometer (see
  [Metrics](#metrics)).
- Target the latest Spring Boot; the module depends on Spring, core stays free of it.

## Website

### Project site on bikesched.org

Publish a project site with GitHub Pages under the already reserved domain `bikesched.org`. Its
sources live in a `page` directory of this repository; it presents the project (pitch, features,
quick start) and the documentation from `docs/`.

- Generator: Jekyll, which GitHub Pages supports natively, or another simple static site generator
  (e.g. Hugo or MkDocs). Build and deploy with GitHub Actions in either case (GitHub's recommended
  way, and required for a `page` source directory or a generator other than Jekyll).
- Reuse `docs/*.md` instead of copying them, e.g. by building them into the site; links between the
  Markdown pages (`jobs.md#job-history`) must keep working on the site.
- Custom domain: DNS records for `bikesched.org` pointing to GitHub Pages, the domain set in the
  repository's Pages settings, HTTPS enforced.
- GitHub Pages for a private repository needs a paid plan (GitHub Pro); on the free plan the
  repository has to be public first.
