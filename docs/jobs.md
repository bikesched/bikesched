# Jobs

A job is a unit of work of a *type*. Every node polls all due jobs, regardless of their type, so all
nodes should register the same handlers.

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

A builder can be reused: every `schedule…` call stores a new job. `schedule(Recurrence)` registers a
[recurring job](recurring-jobs.md) instead. Handlers can also be registered
up front with `bikesched.registerJobHandler(type, handler)`.

To check on a job later, look it up by its id:

```java
Optional<JobInfo> job = bikesched.findJob(id);   // id(), type() and state(), e.g. JobState.SUCCEEDED
boolean done = job.map(info -> info.state().isEnded()).orElse(false);
```

### Typed job types

A `JobType` names a job type together with its payload class. Producer and handler share the
constant, so the compiler checks the payload on both sides:

```java
static final JobType<Order> ORDER = JobType.of("order", Order.class);

bikesched.registerJobHandler(ORDER, (job, order) -> shipping.ship(order));   // order is an Order
UUID id = bikesched.newJob(ORDER).payload(order).schedule();                // payload(Order) only
```

- The builder works as above (`priority`, `connection`, `recurring`, …); `handler((job, order) -> …)`
  sets a typed handler inline. `removeRecurringJob(ORDER)` stops a typed recurring job.
- A handler class can carry its type by implementing `JobTypeHandler<T>` (`type()` plus
  `handle(job, payload)`); `registerJobHandler(handler)` then needs no second argument, e.g.
  `handlers.forEach(bikesched::registerJobHandler)` for all handler beans of a DI container.
  Return a shared constant from `type()`, so producers don't depend on the handler class.
- Only the name is stored, so typed and string-based code work on the same jobs: `newJob("order")`
  schedules a job the `ORDER` handler receives, and vice versa.
- A node rejects using one name with two payload classes (`IllegalArgumentException`).
- The payload class is passed to the `JsonMapper`, so it must be a concrete class like a record;
  wrap generic collections such as `List<Order>` in a record, see
  [Payload design](configuration.md#payload-design).

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

This is the [transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html)
without a separate outbox table: the job is the outbox entry. Writing to your database and calling
another system (a mail server, a message broker, another service) can't be atomic; if the call
fails after the commit, or the commit fails after the call, the two disagree. Scheduling a job in
the same transaction records the intent atomically with your data, and the job then makes the
call, retried on failure up to its maximum attempts. Since a job can run more than once (see
[Failures and retries](#failures-and-retries)), the call should be idempotent, e.g. by passing an
idempotency key derived from the order.

Events can be published the same way, with `.connection(connection)`, see
[Waiting and publishing](events.md#waiting-and-publishing).

## Writing handlers

A handler receives the `Job` being executed:

| Method | Purpose |
|---|---|
| `id()`, `type()` | identity, e.g. for idempotency keys and logging |
| `attempts()`, `maxAttempts()` | the current attempt (starting at 1) and the limit |
| `payload()`, `payload(Class)` | the payload as raw JSON or mapped by the `JsonMapper` |
| `step(name, Class, handler)` | run a durable step, see [Durable steps](steps.md#durable-steps) |
| `newStep(name, Class)` | build a step with a compensation, see [Compensation](steps.md#compensation) |
| `waitForEvent(step, name, key)`, `waitForEvent(step, eventType, key)` | wait for an event, see [Events](events.md) |
| `cancel()`, `cancel(message)` | cancel the job, see [Cancellation](#cancellation) |
| `fail()`, `fail(message)` | fail the attempt without throwing, see [Failures and retries](#failures-and-retries) |
| `isCompensating()` | whether the handler is replayed to compensate the job |
| `updateTimeout(Duration)` | extend the deadline of the running attempt, see [Timeouts](#timeouts) |

bikesched guarantees **at-least-once** execution: a job may run again, e.g. after its node
crashed, so handlers should be idempotent.

## Failures and retries

A handler that throws fails the attempt. The job is retried while attempts remain (`maxAttempts`,
default 3), otherwise it ends `FAILED`; the error of the last attempt is stored in `last_error`.
Retries run as soon as possible unless the node has a [retry backoff](configuration.md).

To fail an attempt without an exception and with control over the retry, use `fail()`:

```java
if (provider.isBusy()) {
    job.fail("provider busy").remainingAttempts(5).retryAfter(Duration.ofMinutes(1));
    return;   // fail() only marks the attempt, it does not stop the handler
}
```

An explicit failure wins over an exception thrown afterwards and over a timeout; cancelling the job
wins over failing it.

A `FAILED` job can be retried once the cause is fixed, as long as it hasn't been deleted after the
[job retention](#job-retention):

```java
bikesched.retry(jobId);   // false if the job does not exist or has not failed
```

It is scheduled right away with all its attempts again and continues where it failed: completed
steps return their stored results (compensated steps run again). The job history records the
retry. Occurrences of recurring jobs can't be retried (`IllegalArgumentException`); the next
occurrence runs anyway.

## Cancellation

```java
bikesched.registerJobHandler("send-mail", job -> {
    if (customerDeleted(job)) {
        job.cancel("customer deleted");   // stored as last_error
        return;                           // cancel() only marks the job, it does not stop the handler
    }
    mailer.send(job.payload());
});
```

A cancelled job ends in state `CANCELLED` and is not retried, even if the handler throws
afterwards.

Jobs can also be cancelled from outside, e.g. when the customer withdraws an order:

```java
boolean cancelled = bikesched.cancel(jobId, "order withdrawn");   // false if it already ended
```

The next node claiming the job cancels it instead of running its handler: right away if it is
scheduled or waiting for an event, after its current attempt if it is running (unless that attempt
completes it). Its unfinished steps, e.g. a wait for an event, become `CANCELLED` and no longer
receive events; completed steps are kept. Steps with a compensation are undone first, see
[Compensation](steps.md#compensation).

## Job history

With `jobHistory(retention)` on the builder, a node records every change of a job and of its steps
in `bikesched_job_history`, in the same transaction as the change, and deletes entries older than
the retention (every `jobHistoryCleanUpInterval`). Without it, nothing is recorded and no extra
statement runs.

```java
Bikesched bikesched = Bikesched.builder().dataSource(dataSource).jobHistory(Duration.ofDays(7)).build();

for (JobHistoryEntry entry : bikesched.findJobHistory(jobId)) {   // oldest first
    System.out.println(entry);
}
```

A job that waited for an event and was retried once could show:

| `stepName` | `state` | `attempt` | `nodeId` | `detail` |
|---|---|---|---|---|
| | `SCHEDULED` | 0 | | `due at 2026-10-04T10:00:00Z` |
| | `RUNNING` | 1 | node-a | |
| `charge` | `RUNNING` | 1 | node-a | |
| `charge` | `FAILED` | 1 | node-a | `java.lang.IllegalStateException: card declined` |
| | `SCHEDULED` | 1 | node-a | `java.lang.IllegalStateException: card declined (retry at …)` |
| | `RUNNING` | 2 | node-b | |
| `charge` | `RUNNING` | 2 | node-b | `step attempt 2` |
| `charge` | `SUCCEEDED` | 2 | node-b | |
| `await` | `WAITING` | 2 | node-b | `waiting for event paid/order-1` |
| | `WAITING` | 1 | node-b | `suspended at step 'await'` |
| `await` | `RECEIVED` | 1 | | `received event 3f2a…` |
| | `SCHEDULED` | 1 | | `resumed by event 3f2a…` |
| | `RUNNING` | 2 | node-a | |
| `await` | `SUCCEEDED` | 2 | node-a | |
| | `SUCCEEDED` | 2 | node-a | |

- Entries without a step name are changes of the job, the others of a step. `state` is the new
  state, or the unchanged one for recorded requests (a cancel request, a failed compensation of a
  step).
- `attempt` is the job's attempt count after the change: suspending at an event wait gives the
  attempt back, resuming counts a new one.
- `nodeId` is the node that made the change; it is empty for changes made by the application:
  scheduling, cancel requests and publishing events.
- Also recorded: cancellations, compensation (`COMPENSATING`, compensation attempts,
  `COMPENSATED` steps), jobs released by a stopping node or from a dead node, occurrences moved to
  a changed recurrence or deleted with their recurring job.
- Only nodes with the history enabled record: enable it on all nodes, or the history has gaps.
  `findJobHistory` reads the table on any node.

## Job retention

Every polling node deletes jobs that ended (`SUCCEEDED`, `FAILED`, `CANCELLED`) longer than the job
retention ago, 7 days by default, together with their steps, every `jobCleanUpInterval` (1 hour).
Set a different retention with `jobRetention(retention)`, the same on all nodes:

```java
Bikesched bikesched = Bikesched.builder().dataSource(dataSource).jobRetention(Duration.ofDays(30)).build();
```

- Scheduled, running, waiting and compensating jobs are never deleted, nor are jobs ending in
  `COMPENSATION_FAILED`, which need attention. Neither are the pending occurrences of recurring
  jobs, only their ended ones.
- The clean-up deletes in batches of 1000 jobs, each in a short transaction, so a large backlog
  does not lock the table for long. Several nodes share the work.
- `findJob` returns empty for a deleted job. The [job history](#job-history) has its own retention
  and keeps entries of deleted jobs until then.

## Timeouts

When an attempt exceeds its timeout (per job, or the node's `defaultJobTimeout`), bikesched logs a
warning and interrupts the handler's thread. Once the handler returns or throws, the attempt counts
as failed and is retried while attempts remain. A handler that is still making progress can extend
its deadline:

```java
while (!encoder.isDone(task)) {
    job.updateTimeout(Duration.ofMinutes(2));   // two more minutes from now
    Thread.sleep(Duration.ofSeconds(10));
}
```

Interrupting only stops handlers that react to it, e.g. blocking calls like `Thread.sleep` or I/O
on virtual threads. A handler stuck in code that ignores the interrupt keeps its attempt `RUNNING`
and its concurrency slot until it returns. With a hard timeout, the node gives up on it after a
grace period:

```java
Bikesched bikesched = Bikesched.builder().dataSource(dataSource)
        .defaultJobTimeout(Duration.ofMinutes(5))
        .hardTimeoutGrace(Duration.ofSeconds(30))
        .build();
```

- If the handler has not returned 30 seconds after the timeout interrupted it, the attempt is
  abandoned: it is recorded as failed like a timed out attempt (retries and compensation apply as
  usual), its slot is freed for other jobs, and the stack trace of its thread is logged once, to
  show where it is stuck.
- The thread cannot be stopped and keeps running. Its later step writes are rejected (its claim of
  the job ended), and once it returns its outcome is discarded. The log counts abandoned threads
  still running on the node; many of them point to a handler that needs fixing.
- Only attempts with a timeout (their own or `defaultJobTimeout`) have a hard timeout.
- It is off by default: an abandoned attempt and its retry can run at the same time, repeating
  side effects outside the database, and abandoned threads keep the resources they hold (e.g.
  database connections). If you use timeouts and your handlers are idempotent (see
  [Idempotency keys](steps.md#idempotency-keys)), set it, e.g. to 30 seconds to a few minutes.

---

Next: [Steps and compensation](steps.md) · [Documentation index](index.md)
