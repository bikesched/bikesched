---
title: "50. Load and failure tests in their own module, run on demand"
weight: 50
---

# 50. Load and failure tests in their own module, run on demand

Date: 2026-10-13

## Status

Accepted

## Context

The regular tests cover each mechanism in isolation, against one shared database that never
fails. What a careful evaluator asks first is what happens when the infrastructure fails: the
database restarts in the middle of a job, a node is cut off past its timeout and comes back, a
connection hangs without a TCP reset; and how many jobs a node runs. Such tests restart databases
and wait out timeouts: minutes per run, too slow and too heavy for every pull request.

Alternatives considered for breaking the network: Toxiproxy (an extra container image and client
library; it breaks all connections of a proxy alike, so it cannot hang the open connections while
new ones work) and Docker network commands (affect every node at once). For a node that stops past
its timeout: a node in a JVM of its own, paused with `kill -STOP` (more code and Linux or macOS
only, for the same path a partitioned node takes: the other nodes remove it, its late results are
rejected, it registers again).

## Decision

bikesched keeps load and failure tests in `bikesched-stress-tests`, a test-only module that is not
published. `./gradlew build` compiles and formats them, but its `test` task is disabled; the
`stressTest` task runs them on demand, before tagging a release. Every test class starts its own
PostgreSQL container, which it may stop and restart; nodes connect through a small TCP proxy per
node that can hang or freeze connections. The throughput number is printed, not asserted.

## Consequences

- Failures are tested end to end, through the real driver, pool and database, without slowing
  down the regular build; the tests cannot rot because they are compiled with every build.
- They run only when someone runs them: the release process has to include them.
- The first run found that a node removed while cut off never recovered (fixed, see
  [ADR 0042](0042-removed-nodes-register-again.md)) and that a `statementTimeout` does not end a
  statement on a hanging connection (documented: the driver's `socketTimeout` does).
