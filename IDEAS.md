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

- Needs an index fitting the query (e.g. `(type, state, updated_at)`), so it costs writes on
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

### Reporting events published in the caller's transaction

`EventListener` does not report events published with `connection(…)`: plain JDBC gives bikesched
no commit callback, and reporting before the commit could count rolled-back events (see ADR 0030).
The Spring starter could register a `TransactionSynchronization` and report after the commit when
the connection belongs to a Spring-managed transaction.

## Testing

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
