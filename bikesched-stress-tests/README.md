# bikesched-stress-tests

Load and failure tests of bikesched: what happens to jobs when the database restarts, a node
pauses past its timeout or a connection hangs, and how many jobs a node runs per second. They
restart databases, pause processes and take a few minutes, so `./gradlew build` compiles them but
does not run them. Run them before tagging a release:

```shell
./gradlew :bikesched-stress-tests:stressTest
```

Requirements: Docker (every test class starts its own `postgres:18` container) and Linux or macOS
(`NodePauseTest` pauses a node with `kill -STOP`; it is skipped on Windows). Run one class with
`--tests`, e.g. `--tests '*NodePauseTest'`.

## Failure tests

The nodes use short timeouts (heartbeat every 500 ms, node timeout 3 seconds, node clean-up every
second, retry after one second), so every failure resolves within seconds. Each test checks that
every job ends, that completed steps are not repeated, and that every node works again afterwards
(it runs a job of a type only it handles).

| Test | Failure | Expected |
|---|---|---|
| `DatabaseRestartTest` | the database restarts while two nodes run 300 jobs with three steps each | every job succeeds; only steps running at the restart run again |
| | the database is down when a handler returns, longer than the node retries recording the outcome | the node releases the job once the database is back, the next attempt succeeds without repeating the completed step |
| | the database is down for twice the node timeout, so each node finds the other's heartbeat expired | every job succeeds, both nodes keep working |
| `NodePauseTest` | a node in its own JVM pauses in a step for half the node timeout | nothing changes, the job succeeds on that node |
| | the node pauses past twice the node timeout | another node runs the job; the resumed node's late step result is rejected, it registers again and runs jobs |
| `HangingConnectionTest` | a network partition: every connection of one node hangs, new ones too, until it heals | another node runs the job; the cut-off node works again after the partition |
| | a failover without a TCP reset: the open connections of one node hang for good, new ones work | with the driver's `socketTimeout`, the node's statements fail on the hanging connections and it carries on with new ones |

The network failures go through a small TCP proxy between each node and the database
(`TcpProxy`), so a test can break one node's network and leave the others alone. The logs of
the paused node's JVM end up in `build/stress-logs/`.

## Load tests

`LoadTest` runs jobs with two steps on one and on three nodes with the default settings and
`maxConcurrentJobs(20)`, checking that no step runs twice while the nodes compete for the jobs:

- backlog: 5,000 jobs scheduled before the nodes start; reports how long the nodes take to run
  them all (jobs per second);
- steady load: 200 jobs per second for 20 seconds, scheduled by an instance that does not poll;
  reports the time from scheduling to the job's start (50th, 95th and 99th percentile, maximum).

The numbers depend on the machine and are reported, not asserted: on the console and appended to
`build/reports/stress/load.txt`. Scale the runs with Gradle properties:

```shell
./gradlew :bikesched-stress-tests:stressTest --tests '*LoadTest' \
    -Pstress.jobs=20000 -Pstress.rate=500 -Pstress.seconds=60 -Pstress.concurrency=50
```

All nodes and the database share one machine here, so three nodes do not run three times as many
jobs: the database is the bottleneck. On separate machines, the numbers are closer to production.
