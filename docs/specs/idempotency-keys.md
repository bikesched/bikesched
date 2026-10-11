# Specification: idempotency keys for jobs and events

Status: **implemented** (core, Kotlin, poll example;
[ADR 0036](../decisions/0036-idempotency-keys.md)). User docs:
[`jobs.md#idempotency-keys`](../jobs.md#idempotency-keys),
[`events.md#idempotency-keys`](../events.md#idempotency-keys), `kotlin.md`, `database.md`.

## 1. Goal

A caller that retries (a webhook delivered twice, a request retried after a timeout) schedules a
job or publishes an event once, without a deduplication table of its own:

```java
UUID id = bikesched.newJob("process-payment")
    .payload(payment)
    .idempotencyKey(webhook.deliveryId())
    .schedule();            // a retry with the same key returns the same id, stores nothing

bikesched.newEvent("payment-received", order.id())
    .payload(payment)
    .idempotencyKey(webhook.deliveryId())
    .publish();             // a retry with the same key returns the first event's id
```

## 2. Scope

In scope:

- `idempotencyKey(String)` on jobs scheduled through `Bikesched` (`JobBuilder`,
  `TransactionalJobBuilder`) and on events published through `Bikesched` (`NodeEventBuilder`,
  also with `connection(…)`).
- Kotlin: an `idempotencyKey` parameter on `createJob` and `Bikesched.publishEvent`.
- Docs (`jobs.md`, `events.md`, `kotlin.md`, `database.md`), an ADR.

Not in scope:

- Child jobs (`StepJobBuilder`) and events published by a job (`StepEventBuilder`): their step
  already makes them exactly-once.
- Recurring jobs: one pending occurrence per type already.
- Looking jobs up by key (`findJob(type, key)`), telling the caller it got a duplicate, "at most
  one active job" semantics (key free again once the job ended).

## 3. Design

### 3.1 API

```java
// org.bikesched.job
public interface JobBuilder<P> … {
  IdempotentJobBuilder<P> idempotencyKey(String key);
}

// new: a one-off job with a key; no schedule(Recurrence)
public interface IdempotentJobBuilder<P>
    extends JobHandlerSettings<IdempotentJobBuilder<P>, P> {
  TransactionalJobBuilder<P> connection(Connection connection);
  UUID schedule();
  UUID schedule(Instant at);
  UUID schedule(Duration delay);
}

public interface TransactionalJobBuilder<P> … {
  TransactionalJobBuilder<P> idempotencyKey(String key);
}

// org.bikesched.event
public interface NodeEventBuilder<P> … {
  NodeEventBuilder<P> idempotencyKey(String key);
}
```

- A key has 1 to 500 characters, like an event key (`ValidationUtil`, `char_length` CHECK).
- `IdempotentJobBuilder` keeps the "mutually exclusive options are separate builder types" rule:
  a key with `schedule(Recurrence)` does not compile, as with `connection(…)`.
- A reused builder keeps its key, so a second `schedule()` returns the first job's id (documented).

### 3.2 Jobs

- The key is unique per job type for as long as the job exists: until the job retention deletes
  it after it ended (`jobRetention`, default 7 days). A webhook redelivered after the job succeeded
  must not run it again.
- `schedule()` with a key: `insert … on conflict (type, idempotency_key) do nothing returning id`;
  without a row, `select id … where type = ? and idempotency_key = ?` and return that id. Its
  payload, due time, priority, attempts and timeout are ignored; the handler is registered as
  usual.
- A duplicate makes nothing due (`db.due` only after an insert), records no job history (nothing
  changed) and is logged at debug level.
- The new job's `SCHEDULED` history entry names the key: `due at …, idempotency key …`. `JobInfo`
  does not expose it.
- In the caller's transaction: PostgreSQL makes the insert wait for a concurrent uncommitted job
  with the same key; it then conflicts (that job committed) or inserts (rolled back). The select
  is a new statement and sees the committed job.
- A job deleted between the conflicting insert and the select (retention): retry the insert once.
- Every ended job keeps its key, also `FAILED`, `CANCELLED` and `COMPENSATION_FAILED` ones: the
  outcome is definitive (retries are bikesched's job, not the caller's). A duplicate returns its
  id; `findJob(id)` tells the caller its state. Another try needs a new key.
- A duplicate while the first job runs returns its id right away: the job's claim already does
  what a request lock (`locked_at`, "409 in progress") does in a request-level implementation.

### 3.3 Events

- The key is unique per event name and event key while the event is unexpired
  (`expires_at > now()`, its retention). With `retention(Duration.ZERO)` the key has no effect;
  the Javadoc of `idempotencyKey` says so, `publish()` does not reject the combination.
- `publish()` with a key: inside the advisory lock it already takes on (name, key), select an
  unexpired event with the same idempotency key; if found, return its id without inserting or
  resuming steps. Scoping to (name, key) is what makes this lock sufficient; a retried publish has
  the same name and key anyway.
- A duplicate does not call `EventListener.onEventPublished` and is logged at debug level.

### 3.4 Storage

Changed in `V001` in place:

- `bikesched_jobs.idempotency_key text` with `char_length(idempotency_key) between 1 and 500`, and
  `create unique index bikesched_jobs_idempotency_key_idx on bikesched_jobs (type,
  idempotency_key) where idempotency_key is not null`.
- `bikesched_events.idempotency_key text` with the same CHECK; no new index (the lookup filters
  `bikesched_events_name_key_idx` rows of one name and key).
- `INSERT_JOB_SQL` with a key becomes a new statement, classified in `DueJobsWritePathsTest`.

### 3.5 Kotlin

```kotlin
bikesched.createJob(type = "process-payment", payload = payment, idempotencyKey = delivery.id)
bikesched.publishEvent(name = "payment-received", key = order.id, payload = payment,
    idempotencyKey = delivery.id)
```

## 4. Rejected alternatives

- **Key free once the job ended** (partial unique index over unended states, like
  `bikesched_jobs_recurring_idx`): a duplicate arriving after the first job finished runs it
  again, which is what the key prevents. "At most one active job" is a different feature.
- **Globally unique job keys**: callers would have to prefix keys per type; per type matches how
  `type` already scopes recurring jobs.
- **Events unique per name only**: needs a second advisory lock on (name, idempotency key).
- **A runtime check against `schedule(Recurrence)` with a key**: against the builder convention.
- **Returning a result type (`Scheduled(id, duplicate)`)**: changes every `schedule` signature
  for a rarely needed flag.
- **A request record per key** (as in
  [brandur.org/idempotency-keys](https://brandur.org/idempotency-keys): `locked_at`, recovery point, request parameters, stored response): that design makes an API
  request resumable. A bikesched job already is: steps are its recovery points, the claim is its
  lock, the poller its completer, and the job row its stored outcome. The key only has to
  deduplicate creating it.

## 5. Testing

- Jobs: same key twice returns the same id, one row; different types with the same key create two
  jobs; the key stays taken after the job succeeded, failed or was cancelled; free again after the
  job was deleted; a duplicate while the job runs; the second call's payload and due time are
  ignored.
- Transactional: duplicate in the same transaction; concurrent transactions with the same key
  (one commits, one rolls back); a rolled-back first job leaves the key free.
- Events: same key twice returns the same id, waiting steps resume once; a different event key
  with the same idempotency key publishes; after expiry it publishes again; with `connection(…)`;
  `onEventPublished` called once.
- Validation: blank and over-long keys rejected; the CHECKs in `SchemaMigratorTest`-style schema
  checks.
- No history entry and no wake-up for a duplicate (`DueJobsWritePathsTest`); the key in the
  `SCHEDULED` entry's detail.
- Kotlin: `createJob` and `publishEvent` with `idempotencyKey`.
- Example: `PollExampleTest` with a webhook delivered twice; the job receives the event once.

## 6. Implementation steps

1. Schema (`V001`), `JobStore` insert with conflict handling, `EventStore.publish` lookup.
2. API: `IdempotentJobBuilder`, `idempotencyKey` on the builders, `NewJob` and the event
   publication carrying the key, `ValidationUtil`.
3. Kotlin parameters.
4. Poll example: the webhook endpoint publishes `payment-settled` with the provider's delivery id
   as key; `SimulatedPaymentProvider` delivers one webhook twice.
5. Docs (`jobs.md`, `events.md`, `kotlin.md`, `database.md`), ADR, `IDEAS.md` entry removed.
   `jobs.md` notes: keys taken from clients need the tenant or user in the key (scope is the job
   type only); handlers calling a provider derive its idempotency key from the job (job id and
   step name, see `steps.md#idempotency-keys`), not from the caller's key.

## 7. Open questions

None.

## 8. Decisions

1. A duplicate with a different payload (or other settings) is ignored: the first call wins, for
   jobs and events. Failing on a mismatch (e.g. a `DuplicateKeyException`) can be added later
   without changing the schema (3.2, 3.3).
2. Duplicates are not stored: no job history entry, no row (3.2).
3. A job's key is taken until the job is deleted after the job retention; no retention of its own
   (3.2).
4. A key on a job selects an `IdempotentJobBuilder` without `schedule(Recurrence)` (3.1).
5. Keys are columns on `bikesched_jobs` and `bikesched_events`, no table of their own (3.4): a
   table would decouple a key's lifetime from its job or event, which decision 3 does not want,
   and cost a second insert, its own clean-up and an expiry rule per owner. It would not save an
   index either: its primary key replaces the partial index, and deleting jobs and events needs
   an index on its references. The partial index holds keyed jobs only, and since neither `type`
   nor the key ever change, it does not make any update of a job non-HOT. Jobs and events use
   different scopes, so a shared table would not share anything. brandur's design has one because
   its keys track request state and are reaped before the records they created.
6. The key shows up in the new job's `SCHEDULED` history entry, not in `JobInfo` (3.2).
7. A key on an event with `retention(Duration.ZERO)` is documented as having no effect, not
   rejected (3.3).
8. The poll example demonstrates the key with a webhook delivered twice (step 4).

## 9. Decided while implementing

1. `JobStore.insertJob` returns the stored job's id; `JobScheduler` recognizes a duplicate by an id
   other than the one it generated. `EventStore.Publication` gained a `duplicate` flag, so
   `DefaultBikesched` skips the debug line and `onEventPublished` for it.
2. The keyed insert is its own statement (`INSERT_KEYED_JOB_SQL`, `INSERT_JOB_SQL` plus the
   `on conflict` clause), classified as notifying in `DueJobsWritePathsTest`; `INSERT_JOB_SQL`
   only gained the column, so occurrences keep their own `on conflict` clause.
3. The example's webhook endpoint receives a `WebhookDelivery(deliveryId, payment)` and prints the
   event id each delivery returned; `Webhook.DELIVERED_TWICE` makes the provider repeat a delivery
   with the same id. `main` takes a third payment with it.
4. Event tests live in `EventIdempotencyKeyTest` (package `event`) next to `IdempotencyKeyTest`
   (package `job`); the length limit and the CHECK constraints are tested in `TextLengthTest`.
5. The README's feature list mentions the key in the scheduling bullet.
