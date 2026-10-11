---
title: "Metrics"
weight: 9
---

# Metrics

A `JobListener` set with `BikeschedBuilder.jobListener(…)` observes the jobs a node runs, an
`EventListener` set with `BikeschedBuilder.eventListener(…)` the events published through it. The
module `bikesched-micrometer` implements both with Micrometer meters; the core stays free of
Micrometer.

## Micrometer

```java
Bikesched bikesched =
    Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("node-1")
        .jobListener(new MicrometerJobListener(registry, Tags.of("node", "node-1")))
        .eventListener(new MicrometerEventListener(registry, Tags.of("node", "node-1")))
        .build();
```

| Meter | Type | Tags | Measures |
|---|---|---|---|
| `bikesched.job.claim.delay` | timer | `job.type` | how long after they were due jobs were claimed; rising values mean the nodes fall behind |
| `bikesched.job.attempts` | timer | `job.type`, `outcome` | attempts by recorded outcome and their duration |
| `bikesched.job.attempts.abandoned` | counter | `job.type` | attempts abandoned by the [hard timeout](jobs.md#timeouts) |
| `bikesched.jobs.active` | gauge | | attempts being processed by the node |
| `bikesched.jobs.abandoned` | gauge | | abandoned attempts whose thread is still running |
| `bikesched.events.published` | counter | `event.name`, `source` | published events; `source` is `node` (`bikesched.newEvent`) or `job` (`job.newEvent`) |
| `bikesched.event.deliveries` | counter | `event.name` | waiting steps that received an event when it was published |

`outcome` is the `JobAttemptOutcome` in lower case: `succeeded`, `retrying`, `failed`, `cancelled`,
`waiting`, `postponed` (the handler called `job.postpone`), `compensating`, `compensated`,
`compensation_retrying`, `compensation_failed`, `failing` (the job's [failure handler](jobs.md#failure-handlers) runs next; its run then reports `failed` or
`compensation_failed`), `failure_handler_retrying` or `not_recorded` (the job was released while it ran, or the database write failed; it runs again).
Alert e.g. on `failed` and `compensation_failed`.

The event meters count an event once, when it is published; a step that starts waiting later and
receives a kept event (see [Retention](events.md#retention)) is not a delivery. Events published in
the caller's transaction (`connection(…)`) are not counted, see [Own listeners](#own-listeners).

Every meter also carries the tags passed to the constructor. Use one listener per node, and give
nodes that share a registry a distinguishing tag such as the node name, otherwise their gauges
collide. The meters only cover what a node does itself; the number of due jobs across all nodes is
a query on `bikesched_jobs` (see [Database](database.md)).

## Own listeners

`JobListener` has three methods, all doing nothing by default:

| Method | Called |
|---|---|
| `onJobClaimed(JobClaim)` | before a claimed job runs; `JobClaim` has the job id and type, the attempt, whether it is a compensation, and the claim delay |
| `onAttemptEnded(JobAttemptResult)` | once per claim, after its outcome was recorded; `JobAttemptResult` has the claim, the `JobAttemptOutcome`, the duration and `isAbandoned()` |
| `onAbandonedAttemptReturned(JobClaim)` | when the thread of an attempt abandoned by the hard timeout finally returns |

The methods are called synchronously on the job's thread, so they must be fast and thread-safe.
Exceptions they throw are logged and ignored. A job's outcome is reported by the node that ran it
after the database commit, so it is lost if that node dies in between.

`EventListener` has one method, doing nothing by default:

| Method | Called |
|---|---|
| `onEventPublished(EventPublication)` | once per published event, after it committed; `EventPublication` has the event id, its name, the `EventSource` (`NODE` or `JOB`) and the number of waiting steps that received it |

It is called synchronously on the publishing thread: before `bikesched.newEvent(…).publish()`
returns, and in a job when its publishing step first publishes the event (not when a later run of
the handler finds the step and returns the stored id). Events published in the caller's
transaction (`connection(…)`) are **not** reported: bikesched does not see whether that
transaction commits or rolls back. The payload and the event key are not part of the
`EventPublication`, since they can be sensitive or high-cardinality. Exceptions are logged and
ignored; `publish()` still returns the event id.

A class can implement both `JobListener` and `EventListener`.
