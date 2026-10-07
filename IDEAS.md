# Ideas

Possible future improvements, not planned yet. Each entry describes the problem and a sketched
solution; remove an entry once it is implemented (or decided against).

## Reliability

### Poller watchdog

With no or a long statement timeout, a hanging connection blocks the single poller thread while the
heartbeat (own thread) keeps the node looking alive. A watchdog could log and recover when a poll
takes far longer than the poll interval.

## Performance

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

bikesched has no hook for a job ending `FAILED` or `COMPENSATION_FAILED`, e.g. to alert or move it to a dead-letter store. The
handler cannot do this reliably itself: it does not run after a timeout, an abandoned attempt or a
crash of its node.

- `JobListener.onAttemptEnded` reports `FAILED` and `COMPENSATION_FAILED` synchronously after the
  commit, by the node recording it (lost if that node dies right after the commit); enough for
  alerts via [Metrics](docs/metrics.md).
- Open: a guaranteed hook (e.g. an outbox-like table or a dead-letter job type), and how it
  relates to the job history.

## Testing

### Load and failure tests

- Benchmarks for throughput and latency per node.
- Failure tests: database restart in the middle of a job (would expose the outcome-recording issue
  above), a node pausing longer than the node timeout, a hanging connection.

## API

### Event listener

There is no callback when an event is successfully published, so metrics cover job execution but not
published events. Add an `EventListener` SPI, analogous to `JobListener`, so an integration can
observe event publication. Define the callback around successful publication: publishing with a
caller-owned transaction must not report success before commit or report rolled-back events. Avoid
exposing payloads or event keys by default, which can be sensitive or high-cardinality.

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

### Fallbacks in event timeout handlers

Verify whether timeout handlers work for fallbacks, e.g.: a Stripe webhook has not arrived yet, so
fetch the payment by its id from Stripe, or if it is not available yet either, try again in 5
minutes.

- The timeout handler runs again on every replay after the deadline, so a fetch inside it is
  repeated; it would need to be a step, inside the handler (timeout handlers may run steps, see
  [Timeouts](docs/events.md#timeouts)) or after the wait.
- "Try again in 5 minutes": the wait step stays `TIMED_OUT`, so waiting again needs a new step
  name, or the job fails the attempt with `job.fail().retryAfter(Duration.ofMinutes(5))`, which uses
  up an attempt and runs the fallback again.
- A webhook arriving after the timeout is not delivered to the timed-out step; it is kept for the
  event retention period, so a later wait (new step name) still receives it.
- Outcome: a documented pattern with a test, or API support (e.g. a timeout handler that can wait
  again).

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
