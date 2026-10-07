# Specification: publishing events from jobs (`job.newEvent`)

Status: **draft, not implemented**. Open questions in §9 are to be settled before implementing.

## 1. Goal

A job handler publishes an event exactly once with respect to the job's progress: the event is
stored and delivered in the same transaction that records the publishing as a completed step, so
neither a crash nor a replay of the handler publishes it twice or loses it.

```java
bikesched.registerJobHandler("ship-order", job -> {
    Order order = job.payload(Order.class);
    Shipment shipment = job.step("ship", Shipment.class, _ -> shipping.ship(order));
    job.newEvent(ORDER_SHIPPED, order.id()).payload(new Shipped(shipment.trackingId())).publish();
    job.step("send-mail", Void.class, _ -> { mail.shipped(order); return null; });
});
```

The same two methods as on `Bikesched`, moved to an interface both implement:

```java
EventBuilder<Object> newEvent(String eventName, String eventKey);
<T> EventBuilder<T> newEvent(EventType<T> eventType, String eventKey);
```

## 2. Today

A handler can call `bikesched.newEvent(…).publish()` (the `Bikesched` captured by the lambda):

| Where | What goes wrong |
|---|---|
| outside a step | published again on every run of the handler: after each event wait, sleep or retry |
| inside a step | at least once: the event commits in its own transaction; if the node dies before the step's result is stored, the step runs again and publishes again |

A second publish of the same name and key is not harmless: steps already completed ignore it, but
a step that started waiting in between receives it, and with a retention the latest copy replaces
the first for later waits.

## 3. Non-goals

- Publishing from a compensation handler (e.g. "order-cancelled"): compensations have no step
  replay of their own; possible later with the same mechanism (§9).
- Publishing in the caller's transaction from a job (`connection(…)`): the job's own step
  transaction is the transaction (§4.3).
- Messaging to systems outside bikesched (Kafka, …): events stay `bikesched_events`.

## 4. Design

### 4.1 Shared interface

`org.bikesched.event.EventPublisher` with both `newEvent` methods; `Bikesched` and `Job` extend it.
Code that only publishes (a helper, a service) can take an `EventPublisher` and work with either.

The two builders differ: from `Bikesched`, `connection(…)` publishes in the caller's transaction;
in a job it makes no sense, but the job needs to name the step (§4.2). Per `AGENTS.md`, options that
only exist on one path belong to separate builder types, not runtime checks. Java allows covariant
return types for overriding (also for generic methods), so:

| Option | Shared interface returns | `Bikesched` returns | `Job` returns | Cost |
|---|---|---|---|---|
| A: one `EventBuilder` | `EventBuilder<P>` | same | same | `connection(…)` throws in a job and `stepName(…)` is ignored outside one: runtime checks |
| **B: base + covariant subtypes (proposed)** | `EventBuilder<P>`: `payload`, `retention`, `publish` | `NodeEventBuilder<P>` adds `connection(…)` | `StepEventBuilder<P>` adds `stepName(…)` | two small interfaces; each subtype overrides `payload`/`retention` covariantly so chains keep their type |
| C: no shared interface | — | `EventBuilder<P>` as today | own `newEvent` returning a job builder | no shared helpers; the user asked for the interface |

Option B keeps every existing call compiling (`bikesched.newEvent(…).payload(…).connection(c)
.publish()` resolves on `NodeEventBuilder`), except code that declares a variable of type
`EventBuilder` and calls `connection` on it. Names are an open question (§9).

### 4.2 Publishing as a step

`publish()` in a job is a durable step:

- **Step name**: by default `publish <eventName>` (event names are at most 200 characters, so the
  prefix needs the limit checked: reject with a hint to `stepName(…)` if the result exceeds 200).
  `stepName("notify-shipping")` overrides it. The key is not part of the default name: keys may be
  500 characters long.
- **Two publishes of the same default name in one run** (e.g. a loop over items): the second one
  throws an `IllegalStateException` asking for `stepName(…)`. `DefaultJob` tracks the publish step
  names used in the current run; because every run executes the same code, the check is stable.
  Without it, the second publish would find the first one's step and silently not publish.
- **First run**: one transaction, fenced by the job lock (`StepStore.requireJobLock`):
  1. insert the step `SUCCEEDED`;
  2. `EventStore.publish(db, …)` as today: advisory lock on name and key, insert the event,
     complete the waiting steps, resume their jobs;
  3. store the received steps as the step's result (see below) and record history for the step and
     the event.
  If the transaction fails or the attempt dies before the commit, nothing is published and the
  next run tries again; after the commit, later runs find the step and skip it.
- **Later runs** return the stored result without publishing.
- **Inside a step handler**: rejected by `StepGuard` like nested steps (a publish is a step). Users
  who want to publish together with their own data write `bikesched.newEvent(…).connection(c)`
  inside the step, accepting at-least-once.
- **Compensation replay**: a `SUCCEEDED` publish step returns its stored result; a missing one ends
  the replay (`CompensationReplayEnded`), as for other steps.

### 4.3 Return value

`publish()` returns `List<ReceivedEvent>` (`jobId`, `stepName`) today. In a job, the list is stored
as the step's result and returned on every run, so the handler sees the same value each time. Core
has no JSON library and the user's `JsonMapper` should not be needed for bikesched's own types, so
the result is written and read in SQL: `jsonb_agg(jsonb_build_object('jobId', …, 'stepName', …))`
when publishing, `jsonb_to_recordset(result)` when replaying. Alternative: return nothing in a job
(`StepEventBuilder.publish()` returning `void`) — simpler, but then the shared interface's
`publish()` cannot be shared (§9).

### 4.4 Delivery to the publishing job itself

A later `waitForEvent` of the same job receives the event like any other job (retention). If the
publishing job itself has a step `WAITING` for that event (only possible when a deploy reordered
its code), the publish completes that step and leaves the job alone because it is `RUNNING`, as
`RESUME_WAITING_STEPS_SQL` does today; the handler's wait then finds the step completed.

### 4.5 Storage

No schema change: a publish step is a regular step (`event_name`/`event_key` null) whose result is
the received list. History: step `SUCCEEDED` with "published event `<id>` `<name>/<key>` to N
step(s)", plus the existing records of the resumed jobs.

### 4.6 Kotlin

`job.publishEvent(name, key, payload, retention, stepName)` as an extension on `Job`, mirroring
`Bikesched.publishEvent`; with the shared interface, one extension on `EventPublisher` could serve
both (only `stepName`/`connection` differ, so probably two extensions; §9).

### 4.7 Rejected alternatives

- **An outbox table** (events recorded by the step, delivered by a background task): a second
  delivery path and latency, while the publish can simply run in the step's transaction because
  events live in the same database.
- **Publishing inside the step handler's transaction** (`job.step` running user code and the
  publish in one transaction): steps don't run user code in a database transaction today, and a
  long step would hold the event's advisory lock.
- **Key in the default step name**: exceeds the 200-character step-name limit for long keys.

## 5. Documentation

`docs/events.md` ("Publishing from jobs": exactly once per job, default step name, `stepName`,
loops), `Job` method table in `docs/jobs.md`, `docs/kotlin.md`, ADR (shared `EventPublisher`,
covariant builder subtypes, publish as a step).

## 6. Testing

1. A job publishes; a waiting job receives it; the publisher's step is `SUCCEEDED` with the
   received list.
2. The publisher suspends afterwards (event wait or sleep) and resumes: the event is not published
   again (one row in `bikesched_events`), `publish()` returns the stored list.
3. Failure between publish and handler end (handler throws after the publish, retried): published
   once.
4. Atomicity: the transaction fails after the event insert (forced, e.g. invalid payload JSON or a
   lost job lock): neither the step nor the event exists.
5. Two publishes with the same default name in one run → `IllegalStateException`; with
   `stepName(…)` both are published.
6. Inside a step handler → rejected; during compensation replay → stored result / replay ends.
7. Typed variant (`EventType`), retention, `ZERO` retention.
8. `EventPublisher` used by a helper with both a `Bikesched` and a `Job`.
9. Existing `bikesched.newEvent(…).connection(c)` tests stay green.

## 7. Implementation steps

1. `EventPublisher` and the builder split (option B) without behaviour change: `Bikesched` returns
   `NodeEventBuilder`; docs samples checked.
2. Store method: publish as a step in one fenced transaction, result as JSON built in SQL, history.
3. `Job.newEvent` in `DefaultJob` with `StepEventBuilder`, default step name, per-run duplicate
   check, `StepGuard`, compensation replay; tests 1–8.
4. Kotlin extension; docs; ADR; remove the entry from `IDEAS.md`.

## 8. Effort

Medium: the API split touches `Bikesched`, the event builder, Kotlin and docs; the store method
reuses `EventStore.publish`.

## 9. Open questions

1. Builder shape: option B (proposed), A or C?
2. Names: `EventPublisher`, `NodeEventBuilder` (from `Bikesched`, with `connection`),
   `StepEventBuilder` (from `Job`, with `stepName`)? Alternatives: `TransactionalEventBuilder` as
   for jobs, but that name means "after `connection(…)`" there.
3. Default step name `publish <eventName>` with `stepName(…)` to override, or a required step name?
4. Return value in a job: the stored `List<ReceivedEvent>` (proposed) or nothing?
5. Publishing from compensation handlers: later, or part of this change?
6. Kotlin: one extension on `EventPublisher` or one per receiver?
