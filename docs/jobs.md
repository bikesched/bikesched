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

A builder can be reused: every `schedule…` call stores a new job. Handlers can also be registered
up front with `bikesched.registerJobHandler(type, handler)`.

To check on a job later, look it up by its id:

```java
Optional<JobInfo> job = bikesched.findJob(id);   // id(), type() and state(), e.g. JobState.SUCCEEDED
boolean done = job.map(info -> info.state().isEnded()).orElse(false);
```

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

## Writing handlers

A handler receives the `Job` being executed:

| Method | Purpose |
|---|---|
| `id()`, `type()` | identity, e.g. for idempotency keys and logging |
| `attempts()`, `maxAttempts()` | the current attempt (starting at 1) and the limit |
| `payload()`, `payload(Class)` | the payload as raw JSON or mapped by the `JsonMapper` |
| `step(name, Class, handler)` | run a durable step, see [Durable steps](steps.md#durable-steps) |
| `newStep(name, Class)` | build a step with a compensation, see [Compensation](steps.md#compensation) |
| `waitForEvent(step, name, key)` | wait for an event, see [Events](events.md) |
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
on virtual threads.

---

Next: [Steps and compensation](steps.md) · [Documentation index](index.md)
