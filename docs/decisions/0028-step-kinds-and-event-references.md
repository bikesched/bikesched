---
title: "28. Step kinds and plain event references"
---

# 28. Step kinds and plain event references

Date: 2026-10-07

## Status

Accepted

## Context

`bikesched_steps` holds regular steps, sleeps and event waits; the kind of a step was inferred from
which columns are null (`event_name` set: event wait; only `wait_until` set: sleep). Event publishes
(ADR 0027) record the event they published, by name, key and id. With `event_name` set they would
look like received event waits, and the null-pattern checks (constraints, "step names must be
unique" checks, the waiting index) could no longer tell them apart.

[ADR 0018](0018-event-payload-copied-into-steps.md) removed `event_id`, which then made receiving
steps depend on the event: a join on resume, a partial index, an anti-join in the clean-up and
events outliving their retention.

## Decision

Steps carry an explicit `kind`: `STEP`, `SLEEP`, `EVENT_WAIT` or `EVENT_PUBLISH`. Constraints, the
waiting index and the step name checks use it. A step name used for another kind of step is
rejected for all kinds, including regular steps.

`event_id` is back as a plain reference without a foreign key and without an index: an event
publish stores the event it published, an event wait the event it received. Nothing joins on it,
and events are still deleted exactly after their retention; the steps keep the id.

The column may be renamed (e.g. to `type`) later; it is internal, there is no public Java type for
it.

## Consequences

- The kind of a step is explicit in the table and in queries (`where kind = 'EVENT_PUBLISH'`).
- Which event a step received or published stays traceable with the job history disabled, also
  after the event was deleted.
- Received payloads are still copied into the steps (ADR 0018); `event_id` adds one value to the
  updates that already run.
