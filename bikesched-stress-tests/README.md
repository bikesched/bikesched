# bikesched-stress-tests

Load and failure tests of bikesched: what happens to jobs when the database restarts, a node is
cut off past its timeout or a connection hangs, and how many jobs a node runs per second. They
restart databases and take a few minutes, so `./gradlew build` compiles them but does not run
them. Run them before tagging a release:

```shell
./gradlew :bikesched-stress-tests:stressTest
```

Requirements: Docker (every test class starts its own `postgres:18` container). Run one class with
`--tests`, e.g. `--tests '*HangingConnectionTest'`.

## Failure tests

The nodes use short timeouts (heartbeat every 500 ms, node timeout 3 seconds, node clean-up every
second, retry after one second), so every failure resolves within seconds. The jobs of
`DatabaseRestartTest` run a step, start a child job (whose first attempt exceeds its timeout), wait
for an event, sleep, wait for the child and run a last step. Each test checks that every job ends,
that completed steps are not repeated, and that every node works again afterwards (it runs a job of
a type only it handles).

| Test | Failure | Expected |
|---|---|---|
| `DatabaseRestartTest` | the database restarts while two nodes run 200 jobs, half of them still waiting for their event | every parent and child succeeds, events published before and after the restart are received; only steps running at the restart run again |
| | the database is down when a handler returns, longer than the node retries recording the outcome | the node releases the job once the database is back, the next attempt succeeds without repeating the completed step |
| | the database is down for twice the node timeout, so each node finds the other's heartbeat expired | every job succeeds, both nodes keep working |
| `HangingConnectionTest` | a network partition: every connection of one node hangs, new ones too, past twice the node timeout | another node runs the job without repeating its completed step; the cut-off node registers again and runs jobs after the partition |
| | a failover without a TCP reset: the open connections of one node hang for good, new ones work | with the driver's `socketTimeout`, the node's statements fail on the hanging connections and it carries on with new ones |

The network failures go through a small TCP proxy between each node and the database
(`TcpProxy`), so a test can break one node's network and leave the others alone.

## Load test

`LoadTest` schedules a backlog of 5,000 jobs with two steps and runs it on one and on three nodes
with the default settings and `maxConcurrentJobs(20)`, checking that no step runs twice while the
nodes compete for the jobs. It prints how long the nodes take (jobs per second); the number depends
on the machine and is not asserted. Scale it with Gradle properties:

```shell
./gradlew :bikesched-stress-tests:stressTest --tests '*LoadTest' \
    -Pstress.jobs=20000 -Pstress.concurrency=50
```

All nodes and the database share one machine here, so three nodes do not run three times as many
jobs: the database is the bottleneck.
