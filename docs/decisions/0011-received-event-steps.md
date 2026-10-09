---
title: "11. Received event steps store the mapped result"
---

# 11. Received event steps store the mapped result

Date: 2026-10-05

## Status

Superseded by [ADR 0018](0018-event-payload-copied-into-steps.md)

## Context

Publishing used to copy the event's payload into every waiting step, and event handlers ran on
every replay, so they could only map. Mapped results could not be stored, and large fan-outs
copied the payload many times.

## Decision

Publishing marks waiting steps `RECEIVED` and only references the event (`event_id`). The job's
next run maps the event with its event handler, stores the result like a step result and marks the
step `SUCCEEDED`; later runs return the stored result. `EventWait.receive(Class, EventHandler)`
therefore takes the result type. The event clean-up keeps expired events while a `RECEIVED` step
references them (`not exists`, backed by a partial index) instead of a foreign key (it would block
or erase references) or an array of steps on the event (two sources of truth, row contention). The
timeout handler's result is not stored: its type is not known to the wait.

## Consequences

- Event handlers run once, like steps; their results must be mappable to JSON.
- One more write per wait; events can outlive their retention while needed.
- A typed wait's `receive()` returns the payload without mapping; `EventWait.Timed` has no
  `receive()` without a class, since the type is erased.
