---
title: "62. Job references read back as nullable values"
weight: 62
---

# 62. Job references read back as nullable values

Date: 2026-10-25

## Status

Accepted

## Context

[ADR 0061](0061-references-for-jobs-and-events.md) returns a job's reference as
`Optional<String> reference()` from `Job`, `FailedJob` and `JobInfo`. On `Job` and `FailedJob`,
the neighbouring values that may be absent (`payload()`, `error()`) are `@Nullable`, and so is
`reference` in the listener records (`JobClaim`, `EventPublication`) and the admin summaries.
Kotlin sees a `@Nullable String` as `String?`, while an `Optional` needs `orElse(null)`. The
lookups (`findJob`) keep returning `Optional`, as the API conventions require.

## Decision

`Job`, `FailedJob` and `JobInfo` declare `@Nullable String reference()`, `null` for a job without
a reference. The other optional values of `JobInfo` (`parentJobId()`, `result(Class)`) stay as
they are.

## Consequences

- Every read of a reference has the same type, in handlers, failure handlers, lookups, listeners
  and the admin summaries.
- Breaking for callers of `reference()` on these interfaces (0.x, unreleased).
