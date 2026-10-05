# 17. Retrying failed jobs keeps their completed steps

Date: 2026-10-05

## Status

Accepted

## Context

A `FAILED` job could only be run again with SQL, e.g. after fixing the bug that made it fail.

## Decision

`Bikesched.retry(jobId)` puts a `FAILED` job back to `SCHEDULED`, due right away, with its
attempts reset and its compensation and cancellation state cleared, in one statement recorded in
the job history ("retried"). Its steps are kept: completed ones return their stored results,
compensated ones run again. `last_error` is kept until the next failure. Retrying an occurrence of
a recurring job throws an `IllegalArgumentException` for now, rather than returning `false`, so
the caller sees why: its next occurrence was already scheduled when it failed, and the partial
unique index allows only one pending occurrence per recurring job.

## Consequences

- Failed jobs continue where they failed instead of starting over.
- Only possible until the job retention deletes the job; `COMPENSATION_FAILED` jobs and failed
  occurrences of recurring jobs cannot be retried this way.
