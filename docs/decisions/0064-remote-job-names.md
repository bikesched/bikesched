---
title: "64. Remote job names"
weight: 64
---

# 64. Remote job names

Date: 2026-11-23

## Status

Accepted

## Context

Scheduling a job fails unless the scheduling node has a handler for its name
(`JobRegistry.requireHandler`). The check catches typos: since [ADR
0016](0016-claim-only-handled-job-types.md) a job of a name no node handles stays `SCHEDULED`
forever, without a warning. [ADR 0046](0046-scheduling-jobs-from-the-admin-ui.md) kept the check
for the admin UI and left a node without handlers for later.

The check also rules out a common setup: a service that runs its own jobs and starts jobs another
service runs (e.g. orders schedules `send-invoice`, which billing handles). Registering a dummy
handler to pass the check does not work on a polling node, because a handler adds the name to the
node's claims, so the node would take those jobs and run the dummy.

Alternatives considered:

- A builder option per call (`newJob(name).withoutLocalHandler()`): every call site has to repeat
  it, and a typo in a call with the option creates a job no node runs. As a mutually exclusive
  option to `handler(…)` it would also need a separate builder type for each of `JobBuilder`,
  `TransactionalJobBuilder` and `IdempotentJobBuilder`.
- A node option turning the check off for all names: loses the typo check for every name.
- Checking that a live node handles the name: nodes do not store the names they handle, and a
  service may legitimately schedule jobs while its workers are down or being deployed.

## Decision

`BikeschedBuilder.remoteJobNames(String...)` declares the job names other nodes handle. The
scheduling node accepts them without a handler of its own (`JobRegistry.requireSchedulable`), for
one-off jobs (also in the caller's transaction and with an idempotency key) and child jobs. Names
are declared once per node, so call sites stay unchanged and an undeclared typo still fails.

A remote job name changes nothing else: the node claims only names it has handlers for, and a
handler registered later for a remote name runs its jobs as usual (logged at info level, since the
declaration then no longer holds). A failure handler on the builder of a remote job without a local
handler is rejected: failure handlers run on the node failing the job, so it would never run.
Recurring jobs still need a
handler, because the node running an occurrence schedules the next one only if the recurring job is
registered on it; the error for a remote name says so.

The admin lists remote job names as schedulable (`JobNameSummary.handled()`), so an admin node
without handlers can schedule them. The Spring Boot starter maps `bikesched.remote-job-names`.

## Consequences

- A service can start jobs of other services without their code, also from a node that polls.
- A declared name with a typo, or one no node handles any more, still creates jobs that stay
  `SCHEDULED`; the declaration is the one place to check.
- `JobNameSummary.handled()` now means "this node can schedule it" rather than "this node has a
  handler"; the name was kept.
