---
title: "Testing"
weight: 15
---

# Testing

`bikesched-test` removes the boilerplate every test of a bikesched-based application repeats: a
uniquely-named test node and polling for a job to reach an expected state.

```java
DataSource dataSource = ...; // the application's own test database

try (Bikesched bikesched = BikeschedTest.testNode(dataSource).jsonMapper(new JacksonJsonMapper()).build()) {
    bikesched.registerJobHandler("send-mail", job -> mailer.send(job.payload()));
    UUID jobId = bikesched.newJob("send-mail").payload(mail).schedule();

    JobInfo job = BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
}
```

The module never starts, resets or migrates a database itself: the caller supplies the
`DataSource`, e.g. a Testcontainers PostgreSQL shared across a test class, exactly as the
[examples](../bikesched-examples/README.md) do.

## Test nodes

`BikeschedTest.testNode(dataSource)` (or `testNode(dataSource, nodeNamePrefix)`) returns a
`BikeschedBuilder` with test-friendly defaults:

- a node name unique to the call, `<nodeNamePrefix>-<random UUID>` (`"test-…"` without a prefix),
  so tests run in parallel without clashing node names;
- a poll interval of 50 ms, so a job scheduled "now" is claimed promptly.

Every other option (JSON mapper, heartbeat interval, concurrency, listeners, job history, …) is set
on the returned builder like any other `Bikesched.builder()`. The caller still calls `build()` and
closes the node, e.g. in a try-with-resources block or an `@AfterEach` method.

## Waiting for a job

`BikeschedTest.awaitJob(bikesched, jobId)` returns a `JobAwait` that polls the node (every 50 ms, up
to a 10 second default timeout) until the job reaches the awaited outcome, then returns the result
read at that point; it throws `org.awaitility.core.ConditionTimeoutException` if the timeout passes
first.

| Method | Waits until | Returns |
|---|---|---|
| `timeout(Duration)` | — (overrides the default timeout) | this `JobAwait`, for chaining |
| `untilEnded()` | the job's state satisfies `JobState.isEnded()` | the job |
| `untilState(JobState... states)` | the job is in one of the given states | the job |
| `untilSucceeded()` | like `untilState(JobState.SUCCEEDED)` | the job |
| `untilSucceededSteps(String... names)` | the job's succeeded steps, in the order they started, are exactly the given names | the succeeded steps |

`JobAwait` is backed by [Awaitility](https://github.com/awaitility/awaitility) internally, but
Awaitility itself is never part of this module's API; a test that needs its full API (e.g. a custom
condition) adds the `awaitility` dependency itself, like the examples already do for anything
`JobAwait` does not cover.

## Non-goals

`bikesched-test` does not own a database (no Testcontainers dependency), does not manage the
node's lifecycle beyond returning a configured builder, and has no JUnit extension, AssertJ
assertions or Spring Boot-specific helper for now; any of these may be added later once repeated
boilerplate in real applications justifies the extra dependency.
