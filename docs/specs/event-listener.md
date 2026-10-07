# Specification: event listener (`EventListener`)

Status: **implemented** (ADR 0030). User docs: `docs/metrics.md`.

## 1. Goal

An integration can observe published events, analogous to the `JobListener` for job execution,
so metrics cover events too, not only jobs:

```java
Bikesched bikesched =
    Bikesched.builder()
        .dataSource(dataSource)
        .eventListener(new MicrometerEventListener(registry, Tags.of("node", "node-1")))
        .build();
```

## 2. Today

`JobListener` (ADR 0020) reports claims and attempt outcomes; `bikesched-micrometer` turns them
into meters. Publishing an event (`bikesched.newEvent(…).publish()` or `job.newEvent(…).publish()`)
reports nothing, so a dashboard can show jobs waiting for events but not the events arriving.

## 3. Non-goals

- Observing the delivery of an event to a step that starts waiting after the publication: that is
  part of the waiting job's attempt (`JobListener`), not of the publication.
- Reporting payloads or event keys: payloads can be sensitive, keys (order ids, …) are
  high-cardinality; a listener needing them can look the event up by id.
- Guaranteed delivery to the listener (an outbox): like `JobListener`, the callback is lost if the
  node dies between the commit and the call.

## 4. Design

### 4.1 SPI

New exported package `org.bikesched.event.listener`, next to `org.bikesched.job.listener`:

```java
public interface EventListener {
  default void onEventPublished(EventPublication publication) {}
}

public record EventPublication(UUID eventId, String eventName, EventSource source, int receivedSteps) {}

public enum EventSource { NODE, JOB }
```

- `eventId`: the stored event's id, the value `publish()` returned.
- `eventName`: the name (low-cardinality like a job type, suitable as a metric tag).
- `source`: `NODE` for `Bikesched.newEvent(…)`, `JOB` for `Job.newEvent(…)` (a publishing step).
- `receivedSteps`: the waiting steps that received the event in the publishing transaction.

Set with `BikeschedBuilder.eventListener(EventListener)`, default none (a no-op listener). One
record per callback, so fields can be added without breaking implementations (`AGENTS.md`).

### 4.2 When it is called

Only after the event committed, synchronously on the publishing thread, once per stored event:

| Path | Called |
|---|---|
| `Bikesched.newEvent(…).publish()` | after bikesched's own transaction committed, before `publish()` returns |
| `….connection(c).publish()` (caller's transaction) | **never** (§4.3) |
| `Job.newEvent(…).publish()`, first run | after the step's transaction committed |
| `Job.newEvent(…).publish()`, later runs | never: the step returns the stored id, nothing is published |
| publish fails (exception) | never |

Exceptions of the listener are logged (`WARN`) and ignored, like `JobListener`'s: `publish()` still
returns the event id.

### 4.3 Caller-owned transactions

With `connection(…)` bikesched neither commits nor sees the commit (plain JDBC has no transaction
callbacks). Reporting at `publish()` could report events that are rolled back later; the idea in
`IDEAS.md` rules that out. So these publications are not reported, which the docs state.

Rejected alternatives:

- Report with an `isCommitted()`/`isInCallerTransaction()` flag: every listener would have to handle
  uncommitted events, and a metric counting them would still count rolled-back ones.
- A callback the caller invokes after its commit (e.g. a returned `Runnable`): changes the
  `publish()` return type and is easy to forget.
- Spring `TransactionSynchronization` in the starter: possible later as a starter-only addition,
  but core cannot do it and the behavior would differ between plain and Spring use.

### 4.4 Implementation

- The `EventListener` is passed from the `Settings` to `JobPoller` (via `JobPollerSettings`) and
  from there to each `DefaultJob`. The static helper `internal.event.EventListenerUtil
  .notifyPublished` builds the record at the calling site, calls the listener, and logs and
  swallows `RuntimeException`s.
- `EventStore.publishEventAsStep` returns a `StepPublication(eventId, publication)`, where
  `publication` is `null` when an earlier run published it; `DefaultJob` notifies only then.
- `DefaultBikesched.publish` notifies only when no caller connection was given.

### 4.5 Micrometer

`bikesched-micrometer` gets `MicrometerEventListener` (separate from `MicrometerJobListener`, so
each can be used alone; core keeps its two runtime dependencies):

| Meter | Type | Tags | Measures |
|---|---|---|---|
| `bikesched.events.published` | counter | `event.name`, `source` | published events (`source`: `node` or `job`) |
| `bikesched.event.deliveries` | counter | `event.name` | waiting steps that received an event when it was published |

Constructor tags are added to every meter, as for `MicrometerJobListener`. No gauges, so nodes
sharing a registry do not collide.

### 4.6 Spring and Kotlin

- Starter: all `EventListener` beans are composed (`CompositeEventListener`, like
  `CompositeJobListener`) and passed to the builder; with `bikesched-micrometer` and a
  `MeterRegistry`, an automatic `MicrometerEventListener` tagged `node` (same conditions and
  `bikesched.metrics.enabled` switch as the job listener). Spring's own `@EventListener`
  annotation has the same simple name; the starter's code imports ours only.
- Kotlin DSL: `eventListener` property next to `jobListener`.

### 4.7 Rejected alternatives

- One `BikeschedListener` for jobs and events: a breaking change to `JobListener` for little gain;
  two SPIs keep each one small, and a class can implement both.
- Methods on `JobListener`: event publication is not job execution, and node-API publications have
  no job.
- Naming it `EventPublicationListener` to avoid the clash with Spring's annotation and
  `java.util.EventListener`: the clash is only an import, `JobListener`/`EventListener` read as a
  pair, and the `IDEAS.md` entry named it so.

## 5. Documentation

`docs/metrics.md` (new meters, own listeners), `docs/configuration.md` (builder option),
`docs/events.md` (link), `docs/spring.md`, `docs/kotlin.md`, ADR 0030; remove the `IDEAS.md` entry.

## 6. Testing

- Core `EventListenerTest`: node publish reports name, id, `NODE`, received steps; caller
  transaction reports nothing (commit and rollback); job publish reports once with `JOB` even when
  the handler replays; a failing listener is logged and `publish()` still returns.
- `MicrometerEventListenerTest`: counters and tags; a node test with a real database.
- Starter: composed listener beans and the automatic Micrometer listener.
- Kotlin DSL: `eventListener` is passed to the builder.

## 7. Implementation steps

1. Core SPI, builder option, settings, notifier, store return type, tests.
2. `MicrometerEventListener` with tests.
3. Starter and Kotlin wiring with tests.
4. Docs, ADR, `IDEAS.md`.

## 8. Decisions

- Caller-transaction publications are not reported (§4.3).
- Keys and payloads are not part of the record (§3).
- Separate `MicrometerEventListener` in the existing `bikesched-micrometer` module (§4.5).
