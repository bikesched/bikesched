---
title: "Jobs"
weight: 2
---

# Jobs

A job is a unit of work of a *type*. A node claims only due jobs of the types it has handlers for,
so different nodes can work on different types; scheduling a job needs a handler for its type on
the scheduling node too.

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

A builder can be reused: every `schedule…` call stores a new job. `schedule(Recurrence)` and
`schedule(String)` register a [recurring job](recurring-jobs.md) instead. Handlers can also be
registered up front with `bikesched.registerJobHandler(type, handler)`. A job scheduled without a time is due
immediately and starts right away on a node handling its type: the scheduling node notifies the
others, see [Due jobs start right away](configuration.md#due-jobs-start-right-away).

A payload that already is JSON text, e.g. a request body, goes in with `.payloadJson(body)`
instead of `.payload(...)`: stored as it is, without the `JsonMapper` (a Jackson mapper would turn
a `String` into a JSON string). Invalid JSON fails `schedule()`.

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

### Idempotency keys

A caller that retries, e.g. a webhook delivered twice or a request retried after a timeout,
schedules the same job twice. With an idempotency key, the job is scheduled once:

```java
UUID id = bikesched.newJob("process-payment")
        .payload(payment)
        .idempotencyKey(webhook.deliveryId())   // continues with an IdempotentJobBuilder
        .schedule();                             // a retry with the same key returns the same id
```

- The key is unique per job type for as long as the job exists: until the [job
  retention](#job-retention) deletes it after it ended, whatever its end state (`SUCCEEDED`,
  `FAILED`, `CANCELLED`). Another try after a definitive outcome needs a new key.
- While a job of the type has the key, `schedule` returns that job's id and stores nothing; the
  duplicate's payload, due time and other settings are ignored. `findJob(id)` tells its state. A
  duplicate while the job runs returns its id right away.
- Keys have 1 to 500 characters. They are scoped to the job type only, so keys taken from clients
  need the tenant or user in the key if several of them share a type.
- `.connection(connection)` works before or after `idempotencyKey`. A duplicate in a concurrent
  transaction waits until the first transaction ends; if it rolls back, the key is free again.
- Recurring jobs have no key (the builder has no `schedule(Recurrence)`): they have at most one
  pending occurrence anyway. Child jobs don't need one: their step starts them once.
- The [job history](#job-history) records the key in the new job's `SCHEDULED` entry; a duplicate
  records nothing.
- The key guards scheduling, not the job's own calls: a handler calling a provider derives that
  provider's idempotency key from the job (job id and step name), see
  [Idempotency keys](steps.md#idempotency-keys), not from the caller's key.

Events have the same option, see [Idempotency keys](events.md#idempotency-keys).

## Writing handlers

A handler receives the `Job` being executed:

| Method | Purpose |
|---|---|
| `id()`, `type()` | identity, e.g. for idempotency keys and logging |
| `attempts()`, `maxAttempts()` | the current attempt (starting at 1) and the limit |
| `payload()`, `payload(Class)` | the payload as raw JSON or mapped by the `JsonMapper` |
| `step(name, Class, handler)` | run a durable step, see [Durable steps](steps.md#durable-steps) |
| `newStep(name, Class)` | build a step with a compensation, see [Compensation](steps.md#compensation) |
| `newEvent(name, key)`, `newEvent(eventType, key)` | publish once as a durable step, see [Publishing from jobs](events.md#publishing-from-jobs) |
| `waitForEvent(step, name, key, Class)`, `waitForEvent(step, eventType, key)` | wait for an event, see [Events](events.md) |
| `awaitEvent(step, eventType, key)` | wait for an event and return its typed payload, see [Typed events](events.md#typed-events) |
| `sleep(step, Duration)`, `sleepUntil(step, Instant)` | pause the job without holding a thread, see [Sleeping](steps.md#sleeping) |
| `newPoll(step, Class, interval)…run(handler)` | check an external service until a result is ready, see [Polling](steps.md#polling) |
| `newJob(type)`, `newJob(jobType)` | start a child job once as a durable step, see [Child jobs](child-jobs.md) |
| `waitForJob(childId, Class)`, `waitForJob(childId, Class, timeout)` | wait for a child job without holding a thread and return its `JobResult`, see [Waiting for children](child-jobs.md#waiting-for-children) |
| `complete(result)` | set the job's result, stored when it succeeds, see [Job results](child-jobs.md#job-results) |
| `cancel()`, `cancel(message)` | cancel the job, see [Cancellation](#cancellation) |
| `fail()`, `fail(message)` | fail the attempt without throwing, see [Failures and retries](#failures-and-retries) |
| `postpone(Duration)` | run the job again later without using up an attempt, see [Postponing](#postponing) |
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
occurrence runs anyway. Neither can [child jobs](child-jobs.md): their parent may have the outcome
already, so the parent starts a new child instead.

## Postponing

`job.postpone(delay)` ends the attempt and runs the job again once the delay has passed, without
using up an attempt and without recording a failure (`last_error` stays as it is). It is meant for
"not now" answers that are no failure: a rate limit, a provider asking to come back later, a
concurrency limit of a library like Resilience4j's `Bulkhead`. Like a [sleep](steps.md#sleeping),
it does not return.

Called in a step's handler, the step keeps no result and gives its attempt back: the next attempt
skips the steps completed before it and runs it again with the same `step.attempts()`. Put the
check in the step, next to the call it guards; code outside steps runs again on every replay. With
[Bucket4j](https://github.com/bucket4j/bucket4j):

```java
String messageId = job.step("send", String.class, step -> {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
        job.postpone(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
    return sms.send(to, text);
});
```

A bucket in Bucket4j's PostgreSQL (or any other distributed) backend holds the limit across nodes;
the [rate-limit example](../bikesched-examples/README.md#rate-limit) runs one.

- The job is `SCHEDULED` while postponed, like a retry; a step postponed from inside is
  `POSTPONED`. The node postponing it runs it again on time; other nodes pick it up with their next
  poll.
- A job can be postponed any number of times. To give up, fail it, e.g. with
  `job.fail("rate limited for too long").remainingAttempts(0)`.
- A job whose cancellation was requested meanwhile is due right away and cancelled.
- It is rejected (`IllegalStateException`) in a compensation, a step's failure handler and a poll's
  check (which has `Poll.notReady()` for "not yet"). While the handler replays to
  [compensate](steps.md#compensation), it does nothing.
- [Job listeners](metrics.md) see the attempt with the outcome `POSTPONED`.

## Failure handlers

A job type can have a failure handler. It runs before a job of the type ends `FAILED` or
`COMPENSATION_FAILED`, whatever made it fail: an exception or a timeout in the last attempt, an
abandoned attempt, a dead node, `fail(…)` without remaining attempts, or failed compensations. Use
it e.g. to move the job to a dead-letter table or to alert someone:

```java
bikesched.registerJobFailureHandler("charge", failed ->
    deadLetters.insert(failed.id(), failed.type(), failed.payload(), failed.error()));

bikesched.registerJobFailureHandler(CHARGE, (failed, charge) ->   // a JobType<Charge>
    deadLetters.insert(failed.id(), charge.orderId(), failed.error()));
```

The handler gets a `FailedJob`: `id()`, `type()`, `payload()`/`payload(Class)`, `state()` (`FAILED`
or `COMPENSATION_FAILED`, the state the job ends in) and `error()`, the stored `last_error`. It gets
no exception: it runs in a claim of its own, possibly on another node.

- The job is `FAILING` until the handler ran, then it ends; the job's outcome is final, the handler
  only reacts to it. Compensations run first.
- A handler that throws or times out (the job's timeout applies) runs again after the node's retry
  backoff, up to `failureHandlerAttempts` runs (default 3, see
  [Configuration](configuration.md#builder-options)); then the job ends anyway, `last_error` keeps
  the job's error. It runs at least once, so it must be idempotent.
- What happens when a job ends happens after the handler: the parent of a
  [child job](child-jobs.md) receives the outcome, the next occurrence of a recurring job is
  scheduled.
- Register it on every node handling the type, like the job handler: the node failing a job decides
  by its own registration. A node claiming a `FAILING` job without one ends the job and logs a
  warning. Registering a second one for a type throws.
- Class-based: `JobTypeFailureHandler<T>` carries its `JobType`, like `JobTypeHandler`; one class can
  implement both and is registered with `registerJobHandler(handler)` and
  `registerJobFailureHandler(handler)`.
- The job builders take one too, registered when the job is scheduled unless the type has one:
  `bikesched.newJob(CHARGE).handler(…).failureHandler((failed, charge) -> …).schedule()`.

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
| `await` | `SUCCEEDED` | 1 | | `received event 3f2a…` |
| | `SCHEDULED` | 1 | | `resumed by event 3f2a…` |
| | `RUNNING` | 2 | node-a | |
| | `SUCCEEDED` | 2 | node-a | |

- Entries without a step name are changes of the job, the others of a step. `state` is the new
  state, or the unchanged one for recorded requests (a cancel request, a failed compensation of a
  step).
- `attempt` is the job's attempt count after the change: suspending at an event wait gives the
  attempt back, resuming counts a new one.
- A [postponed](#postponing) job shows `SCHEDULED` with `postponed until …` (or `postponed in
  step 'charge' until …`); the step it was postponed in shows `POSTPONED` with `until …` before
  it, and its next run has no `step attempt` detail, since it got its attempt back.
- `nodeId` is the node that made the change; it is empty for changes made by the application:
  scheduling, cancel requests and publishing events.
- A [poll](steps.md#polling) records its first wait and its outcome, not every check: its
  further claims and suspensions are left out.
- Also recorded: cancellations, compensation (`COMPENSATING`, compensation attempts,
  `COMPENSATED` steps), failure handlers (`FAILING`, their runs and failures, steps resolved by
  their failure handler), jobs released by a stopping node or from a dead node, occurrences moved to
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
