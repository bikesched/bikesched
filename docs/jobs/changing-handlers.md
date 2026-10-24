---
title: "Changing handlers"
weight: 2
---

# Changing handlers

Jobs outlive deploys: a job sleeping for two weeks, waiting for an event, retried after a failure
or simply scheduled for later runs its next attempt with the handler code deployed by then. This
page explains what bikesched keeps from earlier attempts, which handler changes jobs already in
flight survive, and how to make the changes they don't.

## What a deploy does to jobs in flight

Nothing about the handler's code is stored with a job. Every attempt runs the handler registered
for the job's type on the node that claims it, from the top; completed steps return what they
stored, looked up by their name. What is stored per job:

| Stored | Set by | Used by later attempts |
|---|---|---|
| payload, priority, `maxAttempts`, timeout | scheduling | as stored; a job without a timeout uses the claiming node's `defaultJobTimeout` |
| completed step: name, type, result | the step's first completion | returned instead of running the step again |
| event wait: event name and key, deadline | the first wait | compared with the call's name and key; the deadline wins over a changed timeout |
| sleep: wake-up time | the first sleep | wins over a changed duration |
| poll: deadline, number of checks | the first check | the deadline wins over a changed timeout; the interval comes from the code |
| child job step: the child's id and type | starting the child | returns the same child; a different type is rejected |
| pending occurrence of a recurring job: recurrence description | the previous occurrence's end | moved to a recurrence with another description when registered |

The code decides everything else: which steps run in which order, step handlers that have not
completed yet, compensations, failure handlers, retry backoffs and poll intervals.

Jobs meet the new code whenever they start an attempt after the deploy: jobs `WAITING` for an
event, a sleep, a poll or a child job, jobs `SCHEDULED` for later, for a retry or after
`postpone`, jobs `COMPENSATING` or `FAILING`, and jobs that were `RUNNING` on a node that stopped
(`close()` returns them to `SCHEDULED`, a crashed node's jobs are released). A `FAILED` job meets
it when you [retry](README.md#failures-and-retries) it.

During a rolling deploy, old and new nodes claim the same job types. A job may run its next attempt
on either version, also on an old node after a new one ran it. A change must therefore work in both
directions until the last old node is gone, or be split into two deploys (see
[Making an unsafe change](#making-an-unsafe-change)).

## Safe changes

| Change | Jobs in flight |
|---|---|
| fix a step that has not completed | the next attempt runs the fixed code; jobs that failed in the meantime continue where they failed after `bikesched.retry(jobId)` |
| change code inside a completed step | not run again; only jobs that have not completed the step run the new code |
| add a step after the last step any job in flight has reached | runs once, like any new step |
| reorder steps without changing their names | completed steps return their results wherever they are called; a step moved before the point where a job is waiting runs when it resumes, if it has not run yet |
| add optional properties to payloads, step results, event payloads and job results | stored JSON still maps, see [Payload design](../getting-started/configuration.md#payload-design) |
| change a sleep's duration, a wait's or poll's timeout | started sleeps, waits and polls keep their stored times; new ones use the new values |
| change a poll's interval, a step's `retryBackoff`, its `onFailure` or `compensateWith` handler, a job failure handler | applies from the next check, retry or run; a compensation receives the step's stored result |
| change `priority`, `maxAttempts` or `timeout` on a builder | applies to jobs scheduled afterwards |
| change the recurrence, payload or priority of a recurring job | the pending occurrence moves to a new recurrence but keeps its payload and priority; the node ending an occurrence schedules the next one from its own registration, so during a rolling deploy one more occurrence may follow the old one. See [Recurrences](recurring-jobs.md#recurrences) |

## Unsafe changes

| Change | What happens to jobs in flight |
|---|---|
| rename a completed step | the step runs again under its new name: its side effect happens twice. The old row stays unused, and its compensation no longer runs |
| change a step's result type incompatibly | reading the stored result fails with the `JsonMapper`'s exception: the attempt fails and is retried until the job is `FAILED`. A step that returned `Void` before returns `null` |
| reuse a step name for another step type (a step becomes a sleep, a wait, a poll or a child job) | rejected with an `IllegalStateException` ("step names must be unique within a job"); the attempt fails |
| change the event name or key of a wait | rejected the same way, also for a completed wait: compute keys from the payload or stored results the same way as before |
| change the type of a child job | rejected for a started child. With the default step name `start <type>`, a renamed type is a new step instead and starts a second child; `job.newEvent(name, key)` publishes a second event under its default step name `publish <name>` the same way |
| add a step before steps a job has completed | the job runs the new step on its next attempt, after the later steps already happened |
| remove a step | its stored result is ignored, its compensation no longer runs when the job is cancelled or fails |
| remove a wait a job is waiting at | the job stays `WAITING` until the event arrives, then continues without the wait; without a deadline, publish the event or cancel the job to move it on |
| change a condition outside steps | the job may take another path: the steps of the new path run, completed steps of the old path are neither repeated nor compensated |
| change the payload class incompatibly | mapping the stored payload fails on every attempt |
| change the result class of a child job | the waiting parent fails to read the stored result in `waitForJob` |
| rename or remove a job type | no node claims its jobs any more: they stay `SCHEDULED` or `WAITING`, and `bikesched.cancel` takes effect only once a node handles the type again |
| stop registering a recurring job | its pending occurrence stays; `bikesched.removeRecurringJob(type)` deletes it, see [Occurrences](recurring-jobs.md#occurrences) |

A failing attempt is retried with the node's retry backoff, so an incompatible change usually ends
in `FAILED` jobs with the mapping error or the `IllegalStateException` as `last_error`. Once you
deploy a fix, `bikesched.retry(jobId)` continues them where they failed.

Compensation replays the handler with the new code too: the replay stops at the first step that
has not completed, or when the handler throws, and only the compensations of the steps it reached
run.

## Making an unsafe change

### Branch on a payload version

Mark new jobs in their payload and keep the old path for jobs without the mark. A property missing
from the stored JSON maps to `0` (or `null`), so every job scheduled before the deploy takes the old
path:

```java
record Order(String id, int version) {}   // version 0: scheduled before the change

bikesched.registerJobHandler(ORDER, (job, order) -> {
    Reservation reservation = job.step("reserve", Reservation.class, step -> stock.reserve(order));
    if (order.version() >= 2) {
        job.step("check-fraud", () -> fraud.check(order));   // new step, only for new jobs
    }
    job.step("ship", () -> shipping.ship(order, reservation));
});

bikesched.newJob(ORDER).payload(new Order(orderId, 2)).schedule();
```

Remove the old branch once no job with an older version is left (see
[Draining jobs](#draining-jobs)).

### New job type

For a rewrite, register the new handler under a new type and schedule new jobs only with it. Keep
the old handler registered until the old type's jobs have ended, then remove it:

```java
static final JobType<Order> ORDER = JobType.of("order", Order.class);         // old jobs only
static final JobType<Order> ORDER_V2 = JobType.of("order-v2", Order.class);   // every new job

bikesched.registerJobHandler(ORDER, legacyOrderHandler);
bikesched.registerJobHandler(ORDER_V2, orderHandler);
```

A node claims only types it has handlers for, so old nodes leave `order-v2` jobs to new nodes
during the rolling deploy. A recurring job keeps its type; to change it in an unsafe way, add the
new one under a new type and remove the old one with `bikesched.removeRecurringJob(type)` once no
node registers it.

### Two deploys

When old and new nodes must both run a job during a rolling deploy, split the change: first deploy
code that reads both the old and the new form (a new optional property, a step result type that
accepts both), then deploy the code that writes the new form. The same applies to payloads,
results and events exchanged between services.

### Draining jobs

Before removing a handler, a branch or a type, check that no job still needs it. The
[lookups](../operations/admin.md#lookups-in-code) behind the admin UI count and list them:

```java
Admin admin = bikesched.admin();
admin.findJobTypes();   // jobs per type and state
List<JobSummary> open = admin.findJobs(JobQuery.newest(100).withType("order")
        .withStates(JobState.SCHEDULED, JobState.RUNNING, JobState.WAITING,
                JobState.COMPENSATING, JobState.FAILING));
```

Jobs waiting without a deadline may never end on their own: cancel them, or publish the event they
wait for. `FAILED` jobs stay until the [job retention](README.md#job-retention); a retried job runs
only on a node with a handler for its type.

## Checklist

- Give steps, waits, children and published events stable names and never reuse a name for another
  step type; set `stepName(…)` for child jobs and published events, so renaming their type or event
  name doesn't change the step.
- Keep step results, payloads and job results compatible: add optional properties, don't rename or
  remove them.
- Add new steps at the end, or behind a payload version.
- Keep code outside steps deterministic: base conditions on the payload and on stored step results.
- Keep the handler of a type registered while jobs of the type are in flight.
- Test the new code against jobs the old code suspended: run a job with the old handler on one
  [test node](../integrations/testing.md) until it is `WAITING`, close the node, and resume the job
  on a node with the new handler.
