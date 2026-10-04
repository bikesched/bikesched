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

### Hard timeout for handlers that ignore interrupts

A job timeout only interrupts the handler's thread. A handler stuck in non-interruptible code keeps
its slot and its `RUNNING` row forever, and the heartbeat protects it.

- Builder option `hardTimeoutGrace(Duration)`, off by default.
- If the handler has not returned that long after its timeout fired: record the attempt as failed
  with a timeout error (normal retry and compensation rules apply), free the slot and log the
  thread's stack trace once.
- The thread cannot be killed and keeps running; its later step writes are rejected by the step
  locks (the job is no longer `RUNNING` on the node).
- Count abandoned threads (see [Metrics](#metrics)); optionally stop claiming jobs while there are
  too many.

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

### Metrics

Nothing reports due jobs, the delay between due and claimed, attempt durations, failure rates or
stuck `RUNNING` jobs. A small listener SPI in core, with optional Micrometer support in its own
module, would make the problems above visible in production.

## Testing

### Load and failure tests

- Benchmarks for throughput and latency per node.
- Failure tests: database restart in the middle of a job (would expose the outcome-recording issue
  above), a node pausing longer than the node timeout, a hanging connection.
