# Metrics

A `JobListener` set with `BikeschedBuilder.jobListener(…)` observes the jobs a node runs. The
module `bikesched-micrometer` implements it with Micrometer meters.

## Micrometer

```java
Bikesched bikesched =
    Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("node-1")
        .jobListener(new MicrometerJobListener(registry, Tags.of("node", "node-1")))
        .build();
```

| Meter | Type | Tags | Measures |
|---|---|---|---|
| `bikesched.job.claim.delay` | timer | `job.type` | how long after they were due jobs were claimed; rising values mean the nodes fall behind |
| `bikesched.job.attempts` | timer | `job.type`, `outcome` | attempts by recorded outcome and their duration |
| `bikesched.job.attempts.abandoned` | counter | `job.type` | attempts abandoned by the [hard timeout](jobs.md#timeouts) |
| `bikesched.jobs.active` | gauge | | attempts being processed by the node |
| `bikesched.jobs.abandoned` | gauge | | abandoned attempts whose thread is still running |

`outcome` is the `JobAttemptOutcome` in lower case: `succeeded`, `retrying`, `failed`, `cancelled`,
`waiting`, `compensating`, `compensated`, `compensation_retrying`, `compensation_failed` or
`not_recorded` (the job was released while it ran, or the database write failed; it runs again).
Alert e.g. on `failed` and `compensation_failed`.

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

---

Next: [Database](database.md) · Previous: [Annotations](annotations.md) ·
[Documentation index](index.md)
