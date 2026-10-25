# Specification: references for jobs and events

Status: **implemented** ([ADR 0061](../decisions/0061-references-for-jobs-and-events.md)). User
docs: [`jobs/README.md#references`](../jobs/README.md#references),
[`events.md#references`](../multi-step-jobs/events.md#references), `child-jobs.md`, `kotlin.md`,
`admin.md`, `database.md`, `configuration.md`. Based on `main` at e5afce6 (job name and job type,
#52); aligned with the generic history design in discussion (builders per write action, one
`bikesched_history` table).

## 1. Goal

A caller tags a job or an event with the id of the business entity it is about (an order, a
payment, a webhook delivery) when creating it, and an operator finds everything about that entity
in the admin UI without decoding payloads:

```java
UUID id = bikesched.newJob("charge-order")
    .payload(order)
    .reference("order-4711")
    .schedule();

bikesched.newEvent("payment-received", order.id())
    .payload(payment)
    .reference("pay_3NqK8L2eZvKYlo2C")
    .publish();

bikesched.findJob(id).flatMap(JobInfo::reference);           // Optional[order-4711]
bikesched.admin().findJobs(JobQuery.newest(50).withReference("order-4711"));
```

## 2. Today

- A job has its `name` (the handler), its payload and, optionally, an idempotency key. The key is
  unique per name and is often an external id, but it is a deduplication key: a second job for the
  same order with the same key is not scheduled, and the admin can only look it up together with
  the job name (`Admin.findJob(name, idempotencyKey)`).
- An event has a `name` and a `key` (up to 500 characters, matched by waiting steps), often already
  an entity id; the admin filters events by name and key prefix.
- The admin's search box finds jobs and events by an id suffix only. Finding "the jobs for order
  4711" means guessing the job name and opening payloads one by one.

## 3. Scope

In scope:

- `reference(String)` on every job builder (`JobBuilder`, `IdempotentJobBuilder`,
  `TransactionalJobBuilder`, `StepJobBuilder` for child jobs, recurring jobs through
  `schedule(Recurrence)`) and every event builder (`NodeEventBuilder`, `StepEventBuilder`).
- 1 to 200 characters, like names (`ValidationUtil.requireName`, `char_length` and `octet_length`
  CHECKs). Not unique: many jobs and events may share one reference.
- Set once at creation, never changed.
- Read back: `JobInfo.reference()`, `Job.reference()` (in handlers), `JobSummary.reference()`,
  `EventSummary.reference()`, an MDC key `bikesched.jobReference` while a job with a reference runs,
  and the listener records `JobClaim` and `EventPublication`.
- Lookup: `JobQuery.withReference(String)` and `EventQuery.withReference(String)`, exact match,
  combinable with the other filters, along partial indexes holding only rows with a reference.
- Admin UI: the reference in lists and detail pages, a search by reference, a reference field in
  the schedule and publish forms.
- Kotlin: a `reference` parameter on `createJob`, `createRecurringJob`, `startJob` and both
  `publishEvent`s.
- Docs (`jobs/README.md`, `events.md`, `child-jobs.md`, `kotlin.md`, `admin.md`, `database.md`,
  `configuration.md` for the MDC key), an ADR.

Not in scope:

- Several references per job (tags, a `text[]` or key/value labels), see §6.
- Changing a reference after creation.
- Prefix, substring or case-insensitive search (§10).
- A lookup API on `Bikesched` itself (`findJobs(reference)`): the admin queries cover operators;
  applications keep their own mapping. Can be added later without schema changes.

## 4. Design

### 4.1 API

```java
// org.bikesched.job: shared by all job builders, so child jobs and recurring jobs get it too
public interface JobSettings<B extends JobSettings<B, P>, P extends @Nullable Object> {
  B reference(@Nullable String reference);   // null: no reference (the default)
}

public interface JobInfo {
  @Nullable String reference();
}

public interface Job {
  @Nullable String reference();
}

// org.bikesched.event: shared by NodeEventBuilder and StepEventBuilder
public interface EventBuilder<P extends @Nullable Object> {
  EventBuilder<P> reference(@Nullable String reference);
}

// org.bikesched.admin
public record JobQuery(…, @Nullable String reference, …) { JobQuery withReference(@Nullable String r); }
public record EventQuery(…, @Nullable String reference, …) { EventQuery withReference(@Nullable String r); }
public record JobSummary(…, @Nullable String reference, …) {}
public record EventSummary(…, @Nullable String reference, …) {}
```

- `null` clears the reference, so `newJob(…).reference(job.reference().orElse(null))` works; a
  blank or too long reference is rejected with `IllegalArgumentException` at the builder call.
- The reference sits next to the payload in `JobSettings`, not in its own builder type: it
  combines with every other option (connection, idempotency key, recurrence), so no compile-time
  exclusion is needed.
- With the generic history's builders per action (`cancelJob(id)…cancel()` and so on), the
  reference stays on the creating builders only (`newJob`, `newEvent`); the action builders act on
  an existing job and have no use for it. `reference(…)` and `actor(…)` are independent options of
  the same builders.

### 4.2 Jobs

- Stored in `bikesched_jobs.reference` by every insert path (single, transactional, idempotent,
  child, recurring occurrence).
- Idempotent duplicate: the existing job keeps its reference; the duplicate's reference is ignored
  like its payload.
- Recurring jobs: the registration carries it (`RecurringJob` gets a field) and every occurrence is
  inserted with it. A re-registration with another reference behaves like one with another payload
  (the next occurrences get the new value).
- Child jobs: inherit the parent's reference unless the child's builder sets one, so a
  search for `order-4711` finds the whole tree. The value is resolved when the child is created
  inside its step, so replays create nothing and change nothing.
- `JobLogContext` puts `bikesched.jobReference` into the MDC next to the job id and name, only when
  the job has a reference (no empty MDC entry).
- Job history: the `SCHEDULED` entry's detail names it (`due at …, reference order-4711`), like the
  idempotency key, so the history still shows it after the job's retention. No history column
  (consistent with generic history open question 4: no subject attributes on history rows).

### 4.3 Events

- Stored in `bikesched_events.reference`; an idempotent duplicate keeps the first event's value.
- Events published by a job (`job.newEvent(…)`) inherit the job's reference unless set, like child
  jobs.
- The reference is not matched against waiting steps: the event key stays the correlation key. A
  waiting job does not see the received event's reference (not in scope).
- With the generic history, the `PUBLISHED` entry's detail names it.

### 4.4 Storage

Changed in `V001` in place (before 0.1.0):

```sql
-- bikesched_jobs, after idempotency_key
reference text,   -- an external entity the job is about; set at creation, not unique
constraint bikesched_jobs_reference_check
    check (char_length(reference) between 1 and 200 and octet_length(reference) <= 600),

-- Lookup by reference, newest first. Partial: only jobs with a reference are indexed.
create index bikesched_jobs_reference_idx on bikesched_jobs (reference, id)
    where reference is not null;

-- bikesched_events, after idempotency_key
reference text,
constraint bikesched_events_reference_check
    check (char_length(reference) between 1 and 200 and octet_length(reference) <= 600),

create index bikesched_events_reference_idx on bikesched_events (reference, id)
    where reference is not null;
```

- Cost for rows without a reference: one null bit, no index entry. Rows with one: one index
  entry per insert; the column never changes, so updates of jobs (claims, state changes) stay HOT
  as far as this index is concerned.
- `(reference, id)` serves the admin's page query (`reference = ? and id < ? order by id desc`); a
  name or state filter on top is applied to the few matching rows.
- `SchemaMigratorTest` needs no change (no new table); `database.md` lists both columns and
  indexes.

### 4.5 Admin UI

- Lists: the reference as a secondary line under the job or event name (truncated, full value in
  the title attribute).
- Detail pages: a "Reference" field; clicking it runs the reference search in that tab.
- Search: the existing search box becomes "Id or reference". The server runs both lookups and
  returns the union, newest first: the id-suffix search only when the text is 8 to 32 hex digits
  (as today), the reference search always (exact match, so a reference that happens to be hex is
  still found). The chip says "Matching id or reference …"; the name and state filters stay off
  while searching, as for the id search today.
- `GET api/jobs?reference=…` and `api/events?reference=…` as a filter, combinable with name, state
  and paging (for links and custom tooling).
- Schedule and publish forms: an optional "Reference" field; "schedule similar" and "publish
  similar" copy it.
- A job's detail page links to "events with this reference" and vice versa (a plain link into the
  other tab's search; no combined cross-tab view).

## 5. Rejected alternatives

- **Reuse the idempotency key.** It deduplicates: two jobs for the same order would collapse into
  one, and its scope is per name. Different purpose, different uniqueness.
- **Search inside payloads** (`payload->>'orderId'`, a GIN index on `jsonb`). Needs per-name
  knowledge of the payload shape, indexes every payload, and payloads are deliberately kept out of
  admin summaries.
- **A separate `bikesched_references` table** (entity, job or event id). Allows many references
  per row, but costs an insert and a join for a feature most jobs use with one value, and needs
  its own clean-up.
- **A full (non-partial) index.** Indexes every job, most without a reference.
- **A reference only on events' key.** The event key already is a correlation id, but jobs have no
  equivalent, and the key's semantics (matching waits) must not be overloaded.

## 6. Issues worth knowing

- **Personal data.** References are shown to every admin user and land in logs through the MDC.
  An email address as reference is personal data with the job's retention. The docs say: use
  opaque ids, not names or emails.
- **Overlap with the event key.** For many events the key already is the order id; the reference
  then repeats it. It still helps when the key is something else (a customer id) or to link an event
  to the job tree of one order. The admin search does not match event keys (§10).
- **Recurring jobs.** A reference on a recurring job is the same for every occurrence, which is
  rarely useful (one recurring job per name). Allowed for uniformity, documented as such.
- **One value only.** A job about an order and a payment picks one. If that turns out too narrow,
  a later `text[]` column with a GIN index is a non-breaking extension of the API
  (`references(String...)`), but a schema change.

## 7. Testing

- Core (`ReferenceTest`, `AbstractBikeschedTest`): stored and read back for single, transactional,
  idempotent (duplicate keeps the first), recurring (every occurrence), child (inherited and
  overridden) jobs, and node and step events (inherited and overridden); `null` clears; blank and
  201 characters rejected; a 200-character reference with characters outside the BMP accepted.
- MDC: `bikesched.jobReference` set during the attempt, absent for a job without one.
- Store level: `AdminStore` finds by reference, pages by id, combines with name and state; the plan
  uses the partial index (`explain` assertion as in the existing index tests, if any).
- `DueJobsWritePathsTest`: no new write path (same inserts with one more column).
- History: `SCHEDULED` detail names the reference.
- Admin: API filter, union search (hex text matching an id and a reference), forms; UI checked in
  the browser, light and dark, phone width.
- Kotlin: the new parameters, inheritance through `startJob`.

## 8. Implementation steps

1. Schema (V001), `NewJob` and the event insert, stores, `ValidationUtil` use, `JobInfo`.
2. Builders (`JobSettings`, `EventBuilder`), inheritance for child jobs and job events,
   `RecurringJob`.
3. `Job.reference()`, MDC key, history detail.
4. Admin core API (`JobQuery`, `EventQuery`, summaries), then the admin module (API, UI).
5. Kotlin parameters.
6. Docs, ADR, `IDEAS.md` entry removed, this spec's status.

## 9. Open questions

None left.

## 10. Decisions

- Partial indexes only (`where reference is not null`), so jobs and events without a reference are
  not indexed (Simon, 2026-10-25).
- Up to 200 characters, like names (Simon, 2026-10-25).
- The option is called `reference` (Simon, 2026-10-25). `correlationId` suggests tracing,
  `businessKey` is workflow-engine jargon, `externalId` reads like an id of the job itself.
- One reference per job or event (Simon, 2026-10-25), see §6.
- Child jobs and events published by a job inherit the job's reference unless their builder sets
  one; `reference(null)` sets none (Simon, 2026-10-25).
- Exact match only (Simon, 2026-10-25); a prefix search would need `text_pattern_ops` (or
  `collate "C"`) on the index and can be added later.
- The admin search does not match event keys (Simon, 2026-10-25): without a name it needs an
  index on `key` alone, and the events tab's name and key filters already cover it.
- `JobClaim` and `EventPublication` carry the reference (Simon, 2026-10-25). `bikesched-micrometer`
  does not tag meters with it (unbounded cardinality).

## 11. Decided while implementing

- `FailedJob.reference()` too, so a failure handler can name the entity (e.g. in an alert).
- Admin API: the page's search is a new `search` parameter (ids and references at once, newest
  first, deduplicated), next to the unchanged `id` parameter; `reference` is a list filter that
  combines with the other filters and paging. The page's route parameter is `search` instead of
  `id` (`#/jobs?search=order-4711`).
- The search text is matched as an id only if it is 8 to 32 hex digits (hyphens ignored), as a
  reference always (exact, case-sensitive, at most 200 characters).
- The reference field of the schedule form is under "More options"; "Schedule similar" and
  "Publish similar" copy it. The detail's Reference field links to the jobs and the events with it.
- Kotlin: `startJob` and `Job.publishEvent` take a `reference` too; omitting it keeps the
  inheritance (the builder is not called).
- `Job`, `FailedJob` and `JobInfo` return `@Nullable String reference()` instead of an
  `Optional`, like `payload()` and the listener records
  ([ADR 0062](../decisions/0062-job-reference-read-back-as-nullable.md)).
