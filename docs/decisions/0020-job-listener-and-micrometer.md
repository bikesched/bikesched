---
title: "20. Job listener SPI with Micrometer in its own module"
---

# 20. Job listener SPI with Micrometer in its own module

Date: 2026-10-06

## Status

Accepted

## Context

Nothing reported claim delays, attempt durations, failure rates or abandoned attempts. Core must
not depend on Micrometer (its only runtime dependencies are the JDBC driver and `slf4j-api`), but
applications use Micrometer, other metrics libraries or their own alerting.

## Decision

Core gets a `JobListener` SPI (`BikeschedBuilder.jobListener`) with default no-op methods
`onJobClaimed(JobClaim)`, `onAttemptEnded(JobAttemptResult)` (exactly once per claim, after the
outcome was recorded, with a `JobAttemptOutcome`) and `onAbandonedAttemptReturned(JobClaim)`. They
are called synchronously by `JobProcessor` on the job's thread; exceptions are logged and ignored.
Each method takes a record, so fields can be added later.

The module `bikesched-micrometer` implements it as `MicrometerJobListener`: timers for the claim
delay and for attempts (tags `job.type`, `outcome`), a counter of abandoned attempts and gauges of
active and abandoned attempts. Gauges that need database queries (due jobs, stuck jobs) are left
out, so scraping metrics never hits the database; the claim delay shows a growing backlog.

## Consequences

- Metrics are opt-in and cost nothing without a listener.
- Only events of the reporting node are covered; outcomes are lost if a node dies between the
  commit and the call.
- `JobProcessor` returns structured outcomes instead of log strings.
- Micrometer is an automatic module, so `bikesched-micrometer` suppresses the
  `requires-automatic` lints.
