---
title: "30. Event listener reports committed publications only"
weight: 30
---

# 30. Event listener reports committed publications only

Date: 2026-10-07

## Status

Accepted

## Context

The `JobListener` (ADR 0020) covers job execution, but nothing reported published events, so
metrics showed jobs waiting for events but not the events arriving. A publication can happen in
three ways: through the node in bikesched's own transaction, through the node in the caller's
transaction (`connection(…)`), and from a job as a durable step that later runs of the handler
replay. A listener must not report an event that is rolled back later or report a replayed step
again. Event keys (order ids, …) and payloads can be sensitive or high-cardinality.

Alternatives considered: methods on `JobListener` (node publications have no job, and it would
change an existing SPI); reporting caller-transaction publications with an "uncommitted" flag
(every listener would have to handle it, and counts would include rolled-back events); a callback
the caller runs after its commit (changes `publish()` and is easy to forget).

## Decision

Core gets a second SPI, `org.bikesched.event.listener.EventListener`
(`BikeschedBuilder.eventListener`), with one default no-op method
`onEventPublished(EventPublication)`. The record carries the event id, its name, an `EventSource`
(`NODE` or `JOB`) and the number of waiting steps that received the event; no key and no payload.

It is called synchronously, once per stored event, after the commit: by the node before `publish()`
returns, and by a job when its publishing step first publishes the event. Publications in the
caller's transaction are not reported, since bikesched cannot see that commit. Exceptions are
logged and ignored.

`bikesched-micrometer` gets a separate `MicrometerEventListener` with the counters
`bikesched.events.published` (tags `event.name`, `source`) and `bikesched.event.deliveries` (tag
`event.name`); the Spring starter composes `EventListener` beans and registers the Micrometer
listener under the same conditions as the job listener.

## Consequences

- Core keeps its two runtime dependencies; event metrics are opt-in.
- Events published in a caller's transaction are invisible to listeners and metrics; a Spring-only
  `TransactionSynchronization` could close the gap later (`IDEAS.md`).
- Like job outcomes, a publication is lost to the listener if the node dies between the commit and
  the call.
- The interface shares its simple name with Spring's `@EventListener` and `java.util.EventListener`;
  only imports are affected, and it pairs with `JobListener`.
