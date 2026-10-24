---
title: "59. Job name and job type"
weight: 59
---

# 59. Job name and job type

Date: 2026-10-24

## Status

Accepted

## Context

A job's handler was selected by its *type*, a free-form string in `bikesched_jobs.type`. Steps
use `bikesched_steps.name` for the same kind of identifier and `bikesched_steps.type` for a fixed
set of kinds (`STEP`, `SLEEP`, `EVENT_WAIT`, …). For jobs, the kind was only implied: a recurring
occurrence had a `recurrence`, a child job a `parent_job_id`, everything else neither. Queries,
the admin UI and the partial unique index of recurring jobs each derived it from those columns.

The typed constants of [ADR 0004](0004-typed-job-and-event-types.md), `JobType<T>` and
`EventType<T>`, carried "type" in their names too, which collides with a type that means the kind
of a job.

Alternatives considered: keeping `type` for the handler key and adding a `kind` column (the two
tables would keep using `type` for different things), or keeping the old API names and renaming
only the column (code and schema would disagree).

## Decision

- `bikesched_jobs.name` selects the handler, as `bikesched_steps.name` names a step. The API calls
  it the job name throughout: `newJob(name)`, `registerJobHandler(name, …)`, `Job.name()`,
  `JobInfo.name()`, `JobQuery.withName`, `Admin.findJobNames()`, the MDC key `bikesched.jobName`,
  the Micrometer tag `job.name`, the admin API's `api/job-names` and `name` parameters.
- `bikesched_jobs.type` is the kind of job, the enum `JobType` with `SINGLE`, `RECURRING` and
  `CHILD`, exposed as `JobInfo.type()` and `JobSummary.type()`. bikesched sets it when it stores
  the job; users never pass it. A check constraint ties it to the columns it implies
  (`recurrence` for `RECURRING`, `parent_job_id` for `CHILD`), and the recurring job index and
  queries filter on it instead of `recurrence is not null`.
- The typed constants become `TypedJob<T>` and `TypedEvent<T>`; the class-based handlers become
  `StandaloneJobHandler<T>` and `StandaloneJobFailureHandler<T>`, whose `typedJob()` returns the
  `TypedJob` they handle. A child job step stores the child's name in `child_job_name`.
- The schema changes go into `V001` in place, as bikesched is unreleased.

## Consequences

- Jobs and steps use `name` and `type` the same way, and the kind of a job is one column to read,
  filter on and show.
- A breaking change of the API, the schema, the admin JSON API, the MDC key and the metric tag.
  Local databases need `docker compose down -v`.
- A new kind of job needs a new enum constant and an extension of the check constraint.
