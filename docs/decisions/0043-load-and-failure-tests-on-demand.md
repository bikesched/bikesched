---
title: "43. Load and failure tests in their own module, run on demand"
weight: 43
---

# 43. Load and failure tests in their own module, run on demand

Date: 2026-10-13

## Status

Accepted

## Context

The regular tests cover each mechanism in isolation, against one shared database that never
fails. What a careful evaluator asks first is what happens when the infrastructure fails: the
database restarts in the middle of a job, a node stops dead past its timeout (a long garbage
collection, a frozen VM) and resumes, a connection hangs without a TCP reset; and how many jobs a
node runs. Such tests restart databases, pause processes and wait out timeouts: minutes per run,
too slow and too heavy for every pull request.

Alternatives considered for breaking the network: Toxiproxy (an extra container image and client
library, for two behaviors a few lines of Java provide) and Docker network commands (affect every
node at once). For pausing a node: suspending its threads in the test JVM (not what a pause does:
timers and the heartbeat would keep running).

## Decision

bikesched keeps load and failure tests in `bikesched-stress-tests`, a test-only module that is not
published. `./gradlew build` compiles and formats them, but its `test` task is disabled; the
`stressTest` task runs them on demand, before tagging a release. Every test class starts its own
PostgreSQL container, which it may stop and restart; nodes connect through a small TCP proxy per
node that can hang or freeze connections; a paused node runs in a JVM of its own, paused with
`kill -STOP`. Load numbers are reported, not asserted.

## Consequences

- Failures are tested end to end, through the real driver, pool and database, without slowing
  down the regular build; the tests cannot rot because they are compiled with every build.
- They run only when someone runs them: the release process has to include them.
- `NodePauseTest` needs Linux or macOS.
- The first run found that a node removed while paused never recovered (fixed, see
  [ADR 0042](0042-removed-nodes-register-again.md)) and that a `statementTimeout` does not end a
  statement on a hanging connection (documented: the driver's `socketTimeout` does).
