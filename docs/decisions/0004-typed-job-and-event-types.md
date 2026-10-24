---
title: "4. Typed job and event types next to the string-based API"
weight: 4
---

# 4. Typed job and event types next to the string-based API

Date: 2026-10-05

## Status

Accepted. The records were renamed to `TypedJob<T>` and `TypedEvent<T>` by
[ADR 0059](0059-job-name-and-job-type.md).

## Context

Jobs and events are identified by names, their payloads are JSON. Users want compile-time
types for payloads, but the string-based API must keep working, e.g. for dynamic job types.

## Decision

`JobType<T>` and `EventType<T>` (records: name + payload class) select typed overloads next to
the string-based API (`newJob(JobType)`, `registerJobHandler(JobType, TypedJobHandler)`,
`newEvent(EventType, key)`, `waitForEvent(step, EventType, key)`). Only the name is stored, so both
APIs interoperate. There is no `StepType`: a step is declared and used in one place, so a separate
type constant adds nothing over `step(name, Class, handler)`.

## Consequences

- Builders carry the payload type (`JobBuilder<P>`, `EventBuilder<P>`).
- A node rejects one job name registered with two payload classes; events have no such check.
