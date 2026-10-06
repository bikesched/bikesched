# 18. Event payloads copied into steps, no event handlers

Date: 2026-10-05

## Status

Accepted

Supersedes [ADR 0011](0011-received-event-steps.md)

## Context

ADR 0011 introduced event handlers that map a received event once and store the result: publishing
marked waiting steps `RECEIVED` with a reference to the event (`event_id`), the job's next run
mapped the event and stored the result. This avoided copying the payload, but cost one more step
update per wait, a join with the event on resume, a partial index on steps, an anti-join in the
event clean-up and events outliving their retention while referenced. In the common case (one
waiting step) it did not save writes: the mapped result was stored anyway.

The event handler added little: `Event` only offered the name and the key the caller already knows,
plus the payload. Mapping can happen after `receive`, and anything with side effects belongs in a
step after the wait.

## Decision

Event handlers are removed (`EventHandler`, `Event`, `receive(Class, EventHandler)`). Publishing
copies the event's payload into every waiting step, which becomes `SUCCEEDED` with the payload as
its result; a step that starts waiting after the event was published copies it right away. The
`RECEIVED` state, `bikesched_steps.event_id`, its partial index and the anti-join in the event
clean-up are removed. Users who want smaller stored results design smaller event payloads.

## Consequences

- One step update per wait, no join on resume; events are deleted exactly after their retention.
- The payload is stored twice (event and step), once per waiting step for a fan-out.
- A durable mapping (computed once) needs a step after the wait; pure conversions simply run again
  on every resume.
