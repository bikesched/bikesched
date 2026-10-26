---
title: "Jobs"
weight: 2
---

# Jobs

A job is a unit of work with a *name* that selects its handler. A node claims only due jobs of the
names it has handlers for, so different nodes can work on different jobs; scheduling a job needs a
handler for its name on the scheduling node too.

Each job also has a *type*, `JobType.SINGLE`, `RECURRING` (an occurrence of a [recurring
job](recurring-jobs.md)) or `CHILD` (started by a parent job, see [Child
jobs](../multi-step-jobs/child-jobs.md)). bikesched sets it when the job is created.

## Scheduling jobs

`newJob(name)` returns a builder; all settings are optional:

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
registered up front with `bikesched.registerJobHandler(name, handler)`. A job scheduled without a
time is due immediately and starts right away on a node handling its name: the scheduling node
notifies the others, see [Due jobs start right
away](../getting-started/configuration.md#due-jobs-start-right-away).

A payload that already is JSON text, e.g. a request body, goes in with `.payloadJson(body)`
instead of `.payload(...)`: stored as it is, without the `JsonMapper` (a Jackson mapper would turn
a `String` into a JSON string). Invalid JSON fails `schedule()`.

To check on a job later, look it up by its id:

```java
Optional<JobInfo> job = bikesched.findJob(id);   // id(), name(), type() and state()
boolean done = job.map(info -> info.state().isEnded()).orElse(false);
```

### Typed jobs

A `TypedJob` pairs a job name with its payload class. Producer and handler share the
constant, so the compiler checks the payload on both sides:

```java
static final TypedJob<Order> ORDER = TypedJob.of("order", Order.class);

bikesched.registerJobHandler(ORDER, (job, order) -> shipping.ship(order));   // order is an Order
UUID id = bikesched.newJob(ORDER).payload(order).schedule();                // payload(Order) only
```

- The builder works as above (`priority`, `connection`, `recurring`, …); `handler((job, order) -> …)`
  sets a typed handler inline. `removeRecurringJob(ORDER)` stops a typed recurring job.
- A handler class can carry its typed job by implementing `StandaloneJobHandler<T>` (`typedJob()`
  plus `handle(job, payload)`); `registerJobHandler(handler)` then needs no second argument, e.g.
  `handlers.forEach(bikesched::registerJobHandler)` for all handler beans of a DI container.
  Return a shared constant from `typedJob()`, so producers don't depend on the handler class.
- Only the name is stored, so typed and string-based code work on the same jobs: `newJob("order")`
  schedules a job the `ORDER` handler receives, and vice versa.
- A node rejects using one name with two payload classes (`IllegalArgumentException`).
- The payload class is passed to the `JsonMapper`, so it must be a concrete class like a record;
  wrap generic collections such as `List<Order>` in a record, see
  [Payload design](../getting-started/configuration.md#payload-design).

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
[Waiting and publishing](../multi-step-jobs/events.md#waiting-and-publishing).

### Idempotency keys

A caller that retries, e.g. a webhook delivered twice or a request retried after a timeout,
schedules the same job twice. With an idempotency key, the job is scheduled once:

```java
UUID id = bikesched.newJob("process-payment")
        .payload(payment)
        .idempotencyKey(webhook.deliveryId())   // continues with an IdempotentJobBuilder
        .schedule();                             // a retry with the same key returns the same id
```

- The key is unique per job name for as long as the job exists: until the [job
  retention](#job-retention) deletes it after it ended, whatever its end state (`SUCCEEDED`,
  `FAILED`, `CANCELLED`). Another try after a definitive outcome needs a new key.
- While a job with that name has the key, `schedule` returns that job's id and stores nothing; the
  duplicate's payload, due time and other settings are ignored. `findJob(id)` tells its state. A
  duplicate while the job runs returns its id right away.
- Keys have 1 to 500 characters and at most 1500 bytes in UTF-8 (only characters of 4 bytes, like
  emoji, count more than 3). They are scoped to the job name only, so keys taken from clients
  need the tenant or user in the key if several of them share a name.
- `.connection(connection)` works before or after `idempotencyKey`. A duplicate in a concurrent
  transaction waits until the first transaction ends; if it rolls back, the key is free again.
- Recurring jobs have no key (the builder has no `schedule(Recurrence)`): they have at most one
  pending occurrence anyway. Child jobs don't need one: their step starts them once.
- The [history](#history) records the key in the new job's `SCHEDULED` entry; a duplicate
  records nothing.
- The key guards scheduling, not the job's own calls: a handler calling a provider derives that
  provider's idempotency key from the job (job id and step name), see
  [Idempotency keys](../multi-step-jobs/steps.md#idempotency-keys), not from the caller's key.

Events have the same option, see [Idempotency keys](../multi-step-jobs/events.md#idempotency-keys).

### References

A job is usually about something outside bikesched: an order, a payment, a customer. A reference
names it, so the job can be found by it in the [admin UI](../operations/admin.md) without opening
payloads:

```java
bikesched.newJob("charge-order")
        .payload(order)
        .reference("order-" + order.id())
        .schedule();

bikesched.findJob(jobId).map(JobInfo::reference);   // Optional[order-4711]
```

- Optional, on every job builder (also with `connection(…)`, an idempotency key or a recurrence).
  A reference has 1 to 200 characters and at most 600 bytes in UTF-8, like a job name. It is set
  once and never changes; `reference(null)` sets none.
- Not unique: many jobs and events can share a reference (the charge, the shipment and the
  invoice of one order). To schedule a job once, use an [idempotency key](#idempotency-keys).
- [Child jobs](../multi-step-jobs/child-jobs.md#starting-children) and events published by the
  job have the job's reference unless their builder sets one. Every occurrence of a
  [recurring job](recurring-jobs.md) has the reference it was registered with.
- Handlers read it with `job.reference()` (and failure handlers with `failed.reference()`); while
  a job with a reference runs, it is in the logging context as `bikesched.jobReference` (see
  [Logging](../getting-started/configuration.md#logging)), and listeners get it in `JobClaim`.
- The admin UI searches jobs and events by it; in code, `JobQuery.withReference` and
  `EventQuery.withReference` find them through `bikesched.admin()`. Only jobs and events with a
  reference are indexed.
- Use an opaque id, not a name or an email address: the admin UI shows references to every user,
  and they end up in logs.

## Writing handlers

A handler receives the `Job` being executed:

| Method | Purpose |
|---|---|
| `id()`, `name()` | identity, e.g. for idempotency keys and logging |
| `reference()` | the job's [reference](#references), `null` if it has none |
| `attempts()`, `maxAttempts()` | the current attempt (starting at 1) and the limit |
| `payload()`, `payload(Class)` | the payload as raw JSON or mapped by the `JsonMapper` |
| `step(name, Class, handler)` | run a durable step, see [Durable steps](../multi-step-jobs/steps.md#durable-steps) |
| `newStep(name, Class)` | build a step with a compensation, see [Compensation](../multi-step-jobs/steps.md#compensation) |
| `newEvent(name, key)`, `newEvent(typedEvent, key)` | publish once as a durable step, see [Publishing from jobs](../multi-step-jobs/events.md#publishing-from-jobs) |
| `waitForEvent(step, name, key, Class)`, `waitForEvent(step, typedEvent, key)` | wait for an event, see [Events](../multi-step-jobs/events.md) |
| `awaitEvent(step, typedEvent, key)` | wait for an event and return its typed payload, see [Typed events](../multi-step-jobs/events.md#typed-events) |
| `sleep(step, Duration)`, `sleepUntil(step, Instant)` | pause the job without holding a thread, see [Sleeping](../multi-step-jobs/steps.md#sleeping) |
| `newPoll(step, Class, interval)…run(handler)` | check an external service until a result is ready, see [Polling](../multi-step-jobs/steps.md#polling) |
| `newJob(name)`, `newJob(typedJob)` | start a child job once as a durable step, see [Child jobs](../multi-step-jobs/child-jobs.md) |
| `waitForJob(childId, Class)`, `waitForJob(childId, Class, timeout)` | wait for a child job without holding a thread and return its `JobResult`, see [Waiting for children](../multi-step-jobs/child-jobs.md#waiting-for-children) |
| `complete(result)` | set the job's result, stored when it succeeds, see [Job results](../multi-step-jobs/child-jobs.md#job-results) |
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
Retries run as soon as possible unless the node has a [retry
backoff](../getting-started/configuration.md).

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
steps return their stored results (compensated steps run again). The history records the
retry. Occurrences of recurring jobs can't be retried (`IllegalArgumentException`); the next
occurrence runs anyway. Neither can [child jobs](../multi-step-jobs/child-jobs.md): their parent may
have the outcome already, so the parent starts a new child instead.

## Postponing

`job.postpone(delay)` ends the attempt and runs the job again once the delay has passed, without
using up an attempt and without recording a failure (`last_error` stays as it is). It is meant for
"not now" answers that are no failure: a rate limit, a provider asking to come back later, a
concurrency limit of a library like Resilience4j's `Bulkhead`. Like a
[sleep](../multi-step-jobs/steps.md#sleeping), it does not return.

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
the [rate-limit example](../../bikesched-examples/README.md#rate-limit) runs one.

- The job is `SCHEDULED` while postponed, like a retry; a step postponed from inside is
  `POSTPONED`. The node postponing it runs it again on time; other nodes pick it up with their next
  poll.
- A job can be postponed any number of times. To give up, fail it, e.g. with
  `job.fail("rate limited for too long").remainingAttempts(0)`.
- A job whose cancellation was requested meanwhile is due right away and cancelled.
- It is rejected (`IllegalStateException`) in a compensation, a step's failure handler and a poll's
  check (which has `Poll.notReady()` for "not yet"). While the handler replays to
  [compensate](../multi-step-jobs/steps.md#compensation), it does nothing.
- [Job listeners](../operations/metrics.md) see the attempt with the outcome `POSTPONED`.

## Failure handlers

A job name can have a failure handler. It runs before a job with that name ends `FAILED` or
`COMPENSATION_FAILED`, whatever made it fail: an exception or a timeout in the last attempt, an
abandoned attempt, a dead node, `fail(…)` without remaining attempts, or failed compensations. Use
it e.g. to move the job to a dead-letter table or to alert someone:

```java
bikesched.registerJobFailureHandler("charge", failed ->
    deadLetters.insert(failed.id(), failed.name(), failed.payload(), failed.error()));

bikesched.registerJobFailureHandler(CHARGE, (failed, charge) ->   // a TypedJob<Charge>
    deadLetters.insert(failed.id(), charge.orderId(), failed.error()));
```

The handler gets a `FailedJob`: `id()`, `name()`, `payload()`/`payload(Class)`, `state()` (`FAILED`
or `COMPENSATION_FAILED`, the state the job ends in) and `error()`, the stored `last_error`. It gets
no exception: it runs in a claim of its own, possibly on another node.

- The job is `FAILING` until the handler ran, then it ends; the job's outcome is final, the handler
  only reacts to it. Compensations run first.
- A handler that throws or times out (the job's timeout applies) runs again after the node's retry
  backoff, up to `failureHandlerAttempts` runs (default 3, see
  [Configuration](../getting-started/configuration.md#builder-options)); then the job ends anyway,
  `last_error` keeps the job's error. It runs at least once, so it must be idempotent.
- What happens when a job ends happens after the handler: the parent of a
  [child job](../multi-step-jobs/child-jobs.md) receives the outcome, the next occurrence of a
  recurring job is scheduled.
- Register it on every node handling the name, like the job handler: the node failing a job decides
  by its own registration. A node claiming a `FAILING` job without one ends the job and logs a
  warning. Registering a second one for a name throws.
- Class-based: `StandaloneJobFailureHandler<T>` carries its `TypedJob`, like `StandaloneJobHandler`;
  one class can implement both and is registered with `registerJobHandler(handler)` and
  `registerJobFailureHandler(handler)`.
- The job builders take one too, registered when the job is scheduled unless the name has one:
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
[Compensation](../multi-step-jobs/steps.md#compensation).

## History

With `history(retention)` on the builder, a node records what happens to a job and its steps in
`bikesched_history`, in the same transaction as the change, and deletes entries older than the
retention (every `historyCleanUpInterval`). Without it, nothing is recorded and no extra statement
runs.

```java
Bikesched bikesched = Bikesched.builder(dataSource).history(Duration.ofDays(7)).build();

for (HistoryEntry entry : bikesched.findJobHistory(jobId)) {   // oldest first
    switch (entry) {
        case JobEntry job -> System.out.println(job.action() + " " + job.state());
        case StepEntry step -> System.out.println("  " + step.action() + " " + step.data());
        default -> {}
    }
}
```

A job that waited for an event and was retried once could show (step names and specifics are in
`data`):

| step | `action` | `state` | `attempt` | `actorType` | `data` (besides the names) |
|---|---|---|---|---|---|
| | `SCHEDULED` | `SCHEDULED` | 0 | `APPLICATION` | `dueAt` |
| | `CLAIMED` | `RUNNING` | 1 | `NODE` | |
| `charge` | `STARTED` | `RUNNING` | 1 | `JOB` | |
| `charge` | `FAILED` | `FAILED` | 1 | `JOB` | `error`: `java.lang.IllegalStateException: card declined` |
| | `ATTEMPT_FAILED` | `SCHEDULED` | 1 | `NODE` | `error`, `dueAt` |
| | `CLAIMED` | `RUNNING` | 2 | `NODE` | |
| `charge` | `STARTED` | `RUNNING` | 2 | `JOB` | `run`: 2 |
| `charge` | `SUCCEEDED` | `SUCCEEDED` | 2 | `JOB` | |
| `await` | `WAITING` | `WAITING` | 2 | `JOB` | `eventName`, `eventKey`, `waitUntil` |
| | `SUSPENDED` | `WAITING` | 1 | `NODE` | `stepName`: `await` |
| `await` | `RECEIVED` | `SUCCEEDED` | 1 | `APPLICATION` | |
| | `RESUMED` | `SCHEDULED` | 1 | `APPLICATION` | |
| | `CLAIMED` | `RUNNING` | 2 | `NODE` | |
| | `SUCCEEDED` | `SUCCEEDED` | 2 | `NODE` | |

- `findJobHistory` returns a `JobEntry` for each change of the job and a `StepEntry` for each
  change of one of its steps (`stepId`). Both are `HistoryEntry`s in `org.bikesched.history`,
  next to `EventEntry` (a [published event](../multi-step-jobs/events.md), read with
  `findEventHistory`) and `NodeEntry` (a node `REGISTERED`, `STOPPED` or `REMOVED` by another
  node's clean-up, read with `admin().findNodeHistory`).
- `causedById` links an entry to the event, job or node that caused it: the steps an event
  completed and the jobs it resumed name the event, a [child job](../multi-step-jobs/child-jobs.md)
  its parent, the parent's step and resumption the child, a job released after its node died that
  node. A job's own follow-ups (a cancel request, then the cancellation) are not caused entries.
- `action` says what happened (`JobAction`, `StepAction`; their Javadoc lists each action's `data`
  keys), `state` is the job's or step's state afterwards: unchanged for recorded requests (a cancel
  request, a failed compensation of a step).
- `attempt` is the job's attempt count after the change: suspending at an event wait gives the
  attempt back, resuming counts a new one.
- `actorType` says who acted: `NODE` (the node by itself: claims, timeouts, releases, wake-ups),
  `APPLICATION` (calls of the `Bikesched` API: scheduling, publishing, cancel requests, retries)
  or `JOB` (a job handler: its steps, child jobs, events it publishes), or `USER` with the user's
  name in `actorName`. `nodeId` is the node that wrote the entry, also for the application's
  calls; it is empty for writes of an instance that does not poll and so never registered.
- A user is named with `actor(Actor.user(name))` on `newJob(…)` and `newEvent(…)` and on the
  actions under [`bikesched.admin()`](../operations/admin.md#lookups-and-actions-in-code)
  (`cancelJob`, `retryJob`, `removeRecurringJob`); the admin UI names the signed-in user. The user
  goes only on the entry of the action itself (`SCHEDULED`, `PUBLISHED`, `CANCEL_REQUESTED`,
  `RETRIED`): what it causes, such as the jobs an event resumes, keeps the code path's actor and
  names the cause in `causedById`. Job handlers cannot name a user.

  ```java
  bikesched.newEvent("payment-received", orderId).actor(Actor.user("simon")).publish();
  bikesched.admin().retryJob(jobId).actor(Actor.user("simon")).retry();
  ```
- `data` is a JSON object as text, or `null` without specifics: the job's name (`subjectName`), the
  step's name (`stepName`), and the action's specifics, such as `error` and `message` (cut to 4000
  characters), `dueAt`, `waitUntil`, `reason` or `checks`. The names are stored, so the entries
  still read well after the job was deleted.
- A [postponed](#postponing) job shows `POSTPONED` with `dueAt` (and the `stepName` it was
  postponed in); that step shows `POSTPONED` before it, and its next run has no `run`, since it got
  its attempt back.
- A [poll](../multi-step-jobs/steps.md#polling) records its first wait and its outcome (`checks`),
  not every check: its further claims and suspensions are left out.
- Also recorded: cancellations, compensation (`COMPENSATING`, failed compensations,
  `COMPENSATED` steps), failure handlers (`FAILING`, their runs and failures, steps `RESOLVED` by
  their failure handler), jobs `RELEASED` by a stopping node or from a dead node (`reason`),
  occurrences `MOVED` to a changed recurrence or `DELETED` with their recurring job. A duplicate
  of an idempotency key and a retry of a job that did not fail record nothing: nothing changed.
- Entries are ordered by the database's time of the write, then by their id (UUIDv7).
- Only nodes with the history enabled record: enable it on all nodes, or the history has gaps.
  `findJobHistory` reads the table on any node.

## Job retention

Every polling node deletes jobs that ended (`SUCCEEDED`, `FAILED`, `CANCELLED`) longer than the job
retention ago, 7 days by default, together with their steps, every `jobCleanUpInterval` (1 hour).
Set a different retention with `jobRetention(retention)`, the same on all nodes:

```java
Bikesched bikesched = Bikesched.builder(dataSource).jobRetention(Duration.ofDays(30)).build();
```

- Scheduled, running, waiting and compensating jobs are never deleted, nor are jobs ending in
  `COMPENSATION_FAILED`, which need attention. Neither are the pending occurrences of recurring
  jobs, only their ended ones.
- The clean-up deletes in batches of 1000 jobs, oldest first, each in a short transaction, so a
  large backlog does not lock the table for long. Several nodes share the work. The clean-ups of
  the history and of expired events work the same way.
- `findJob` returns empty for a deleted job. The [history](#history) has its own retention
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
Bikesched bikesched = Bikesched.builder(dataSource)
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
  [Idempotency keys](../multi-step-jobs/steps.md#idempotency-keys)), set it, e.g. to 30 seconds to a
  few minutes.
