---
title: "67. Result types on typed jobs"
weight: 67
---

# 67. Result types on typed jobs

Date: 2026-11-27

## Status

Accepted

## Context

A `TypedJob<P>` named a job and its payload type; the result type was passed to every wait
(`waitForJob(id, Receipt.class)`), and [ADR 0029](0029-child-jobs-as-steps.md) rejected handlers
returning results in favour of the `job.complete(result)` flag. Job batches
([ADR 0066](0066-child-job-batches-with-held-items.md)) read the results of many items: a class
per read is repetitive, and a wrong one fails only when the result is mapped.

## Decision

bikesched declares a job's result type next to its payload type:

- `TypedJob` becomes a sealed interface with two records: `PayloadJob<P>` (from
  `TypedJob.of(name, payloadType)`, the former record) and `ResultJob<P, R>` (from
  `TypedJob.of(name, payloadType, resultType)`). Every API taking a `TypedJob` accepts both.
- `Bikesched.registerResultJobHandler(resultJob, (job, payload) -> result)` registers a
  `ResultJobHandler` whose return value is stored as `job.complete(result)` would store it, with
  the same precedence (`cancel()` and `fail()` win). It has a name of its own: an implicitly typed
  lambda would match both it and `registerJobHandler(TypedJob, TypedJobHandler)`.
- `job.waitForJob(id, resultJob)` and a batch of a `ResultJob` read results with its
  `resultType()`, without a class.
- Kotlin gets `handleResultJobs<P, R>(name) { payload -> result }`, a name of its own because a
  `handleJobs<P, R>` would erase to the signature of `handleJobs<T>`.

This replaces ADR 0029's rejection of results returned from handlers; `job.complete` stays for
every other handler type.

## Consequences

- Results are typed once, at the declaration, and batches are typed end to end.
- Breaking at 0.x: `new TypedJob<>(…)` and record patterns on `TypedJob` no longer compile, and
  code compiled against the record fails with `IncompatibleClassChangeError` until recompiled.
- Not covered yet: results returned by `StandaloneJobHandler`, `@JobHandler` methods and the
  Spring starter's discovery; they keep calling `job.complete`.
