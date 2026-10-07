# 27. Publish events from jobs as durable steps

Date: 2026-10-07

## Status

Accepted

## Context

Publishing through `Bikesched.newEvent` from a handler is not tied to job progress. A replay may
publish again, and a crash after a separate event transaction but before a step commits can
publish again. Since events and steps share PostgreSQL, the event and a durable publishing step can
be committed together.

## Decision

`Bikesched` and `Job` share the `EventPublisher` API. The base `EventBuilder` has only common
operations; `NodeEventBuilder` adds caller-transaction publishing, while `StepEventBuilder` adds a
step name. A job event publish runs in one transaction fenced by the job lock and records an
`EVENT_PUBLISH` step (see [ADR 0028](0028-step-kinds-and-event-references.md)) with the event's
name, key and id. The default step name is `publish <eventName>`; callers publishing repeatedly
must supply distinct names.

`publish()` returns the id of the published event, for nodes and jobs alike, so callers can log it,
return it or store it with their own entities. It does not return the steps that received the
event: they are only a snapshot of who waited at that moment (with retention, later waits receive
it too), so code should not branch on them. The job history records them.

## Consequences

A job replay returns the stored event id and does not publish again; a replay publishing a
different event under the same step name is rejected. Publishing inside another step or during
compensation is not supported. `ReceivedEvent` is removed.
