# Specification: history of jobs, events, nodes and user actions

Status: **implementing**: step 1 of 4 (table and entries) is implemented, see
[ADR 0062](../decisions/0062-one-history-with-actions-and-actors.md) and the
[History](../jobs/README.md#history) docs.

## 1. Goal

One history answers "what happened, and who did it" for more than jobs: changes of jobs and their
steps (recorded today), published events, nodes registering, stopping and being removed, and the
actions users take in the admin UI (or any other tool acting for a person), with the user's name.

```java
Bikesched bikesched =
    Bikesched.builder().dataSource(dataSource).history(Duration.ofDays(7)).build();

// writes that are plain methods today become builders; a user can be named on each
bikesched.cancelJob(jobId).message("customer called").actor(Actor.user("simon")).cancel();
bikesched.retryJob(jobId).actor(Actor.user("simon")).retry();
bikesched.newEvent("paid", "order-1").actor(Actor.user("simon")).publish();

for (HistoryEntry entry : bikesched.findJobHistory(jobId)) {
  switch (entry) {
    case JobEntry job -> log.info("{} {} by {}", job.action(), job.state(), job.actorType());
    case StepEntry step -> log.info("step {} {}", step.stepId(), step.action());
    default -> {}
  }
}
bikesched.findEventHistory(eventId); // PUBLISHED by user simon, and the steps it reached
```

The admin UI gets a History tab listing every entry, newest first, filtered by actor, subject and
action.

## 2. Today

- `bikesched_job_history` ([ADR 0007](../decisions/0007-job-history-in-separate-table.md)): one
  row per change of a job (`step_name` null) or of one of its steps, written in the transaction of
  the change by `HistoryStore.record`, only on nodes with `jobHistory(retention)`. Columns:
  `job_id`, `node_id`, `seq` (global identity), `created_at` (`now()`), `attempt`, `state`,
  `step_name`, `detail`. [ADR 0057](../decisions/0057-steps-keyed-by-a-time-ordered-id.md) kept
  `step_name` rather than the step id.
- `state` is the new state, or the unchanged one for requests (a cancel request); what happened is
  prose in `detail` (`retried`, `resumed by event …`, `released from dead node …`).
- Who made a change is only `node_id`: set for changes a node makes by itself, `null` for the
  application (scheduling, cancel requests, publishing). There is no notion of a user.
- Events have no entries of their own: publishing records the receiving steps and the resumed jobs
  only. An event nobody waited for leaves no trace, and once `EventCleanUp` deleted it, nothing
  says who published it. Nodes have no entries at all.
- The admin's actions (`AdminHandler.post`: retry, cancel, schedule, publish) name the user only in
  a log line and, for cancel, in the cancel message.
- `Bikesched.cancel(UUID)`, `cancel(UUID, String)`, `retry(UUID)` and `removeRecurringJob(…)` are
  plain methods.

## 3. Scope

In scope:

- One table `bikesched_history` replacing `bikesched_job_history`, for job, step, event and node
  entries.
- An action on every entry (what happened), an enum per subject type, next to the resulting state.
- An actor on every entry: a node by itself, the application, a job handler, or a named user.
- A link to the event, job or node that caused an entry (`caused_by_id`).
- Names and an action's specifics stored with the entry (`data`, JSON built in SQL), so a deleted
  job, step, event or node still reads well.
- Builders for the writes that are plain methods today (cancel, retry, removing a recurring job),
  each with an `actor(…)` option, as on the job and event builders.
- Read API: `findJobHistory`, `findEventHistory`, and `Admin.findHistory(HistoryQuery)` for the
  History tab.
- Admin UI: the History tab, actions and actors in the job, step, event and node pages, the admin's
  writes naming the signed-in user.

Not in scope:

- Recurring jobs as a subject (registered, recurrence changed, removed): needs a stable id for a
  recurring job, today only a name.
- Recording without the history setting: the history stays completely optional, per node; a node
  without it records nothing, user actions included.
- A separate retention for user entries (`IDEAS.md`: "Longer retention for user history entries").
- Reads, denied requests and failed logins: the access layer logs them.
- Idempotency duplicates (a schedule or publish returning an existing job or event) and no-ops (a
  retry of a job that did not fail): nothing changed, nothing is recorded.
- Expiry and deletion by the clean-ups.
- A `HistoryListener` SPI.

## 4. Design

### 4.1 Table

Module `bikesched-core`, migration `V001` in place (no release yet).

```sql
create table bikesched_history (
    subject_id   uuid        not null,   -- the job (also for step entries), event or node
    id           uuid        not null,   -- UUIDv7 from the library
    step_id      uuid,                   -- JOB entries of a step
    subject_type text        not null,   -- JOB, EVENT, NODE
    action       text        not null,   -- what happened, one enum per subject type (and steps)
    state        text,                   -- JOB entries: the resulting job or step state
    node_id      uuid,                   -- the node that wrote the entry, if registered
    caused_by_id uuid,                   -- the event, job or node that caused it
    created_at   timestamptz not null default clock_timestamp(),
    attempt      integer,                -- JOB entries: the job's attempt count after the change
    actor_type   text        not null,   -- NODE, APPLICATION, JOB, USER
    actor_name   text,                   -- USER entries: the user name
    data         jsonb,                  -- names and the action's specifics, built in SQL
    constraint bikesched_history_pkey primary key (subject_id, id),
    constraint bikesched_history_data_check check (jsonb_typeof(data) = 'object'),
    constraint bikesched_history_subject_type_check
        check (subject_type in ('JOB', 'EVENT', 'NODE')),
    constraint bikesched_history_actor_type_check
        check (actor_type in ('NODE', 'APPLICATION', 'JOB', 'USER')
               and (actor_name is not null) = (actor_type = 'USER')),
    constraint bikesched_history_job_check
        check ((attempt is not null) = (subject_type = 'JOB')
               and (state is not null) = (subject_type = 'JOB')
               and (step_id is null or subject_type = 'JOB')
               and attempt >= 0),
    constraint bikesched_history_action_check
        check (case
                   when subject_type = 'JOB' and step_id is null then
                       action in (/* JobAction, 4.4 */) and state in (/* job states */)
                   when subject_type = 'JOB' then
                       action in (/* StepAction */) and state in (/* step states */)
                   when subject_type = 'EVENT' then action in ('PUBLISHED')
                   else action in ('REGISTERED', 'STOPPED', 'REMOVED')
               end),
    constraint bikesched_history_actor_name_check
        check (char_length(actor_name) between 1 and 200 and octet_length(actor_name) <= 600)
);

-- Deleting entries after the retention; the History tab, newest first.
create index bikesched_history_created_at_idx on bikesched_history (created_at);

-- The entries an event, job or node caused.
create index bikesched_history_caused_by_id_idx on bikesched_history (caused_by_id)
    where caused_by_id is not null;

-- The History tab's Users filter.
create index bikesched_history_user_idx on bikesched_history (created_at)
    where actor_type = 'USER';
```

- The primary key leads with `subject_id`, so a job's history (steps included), an event's or a
  node's is one index range, as today. `subject_type` is not part of it: ids are UUIDv7 from one
  generator and unique across types, and every lookup would have to pass the type.
- Column order follows ADR 0056: identifying columns, action and state first.
- No foreign keys: entries outlive their subjects for the history retention.
- Cost on the largest table, measured with 100k job and step rows on PostgreSQL 16: rows from 98
  to 185 bytes on average (156 with the names as text columns instead of in `data`), indexes from
  5.4 to 8.4 MB.

### 4.2 Ordering

- Entries are ordered by (`created_at`, `id`) within a subject and across subjects.
- `created_at` is `clock_timestamp()`: the database's clock when the row is written, one clock for
  all nodes. `now()` is the transaction's start, so a long transaction's entry could sort before
  an entry that committed earlier.
- `id` (UUIDv7) carries the writing node's clock, so it only breaks ties: rows of one statement
  come from one JVM, whose ids are strictly increasing.
- No global sequence and no stored per-job number. Numbering per job is only safe if every
  recording transaction holds the job's row lock, and publishing completes a waiting step without
  locking a job that is not `WAITING`. A per-job advisory lock was rejected (5). A per-job number
  can be computed when reading (`row_number()`).
- No lock for ordering: serialized changes take their row lock after the earlier change
  committed, so their time is later; unserialized ones have no single true order.
- The indexes on `created_at` hold no `id`: ties are rare (microseconds) and sorted after the
  fetch; keyset paging by (`created_at`, `id`) still works.

### 4.3 Recording

- `HistoryStore.record(Sql, List<Change>)` keeps writing all entries of a transaction with one
  `insert … select … from unnest(…)` statement, in the transaction of the change; with the
  history disabled it does nothing and changes run without an explicit transaction, as today.
- The statement builds `data` itself; Java passes raw values only:

  ```sql
  nullif(jsonb_strip_nulls(jsonb_build_object(
      'subjectName', coalesce(j.name, e.name, n.name, t.subject_name),
      'stepName', s.name,
      'error', left(t.error, 4000),
      'message', left(t.message, 4000),
      'key', e.key,
      …)), '{}'::jsonb)
  ```

  joined by primary key to the rows the transaction just wrote (`bikesched_jobs`,
  `bikesched_steps`, `bikesched_events`, `bikesched_nodes`). Callers pass a name only when the row
  is gone (`DELETED` jobs, `REMOVED` nodes). The keys are a closed list in this one statement,
  documented per action; a new key is a code change, not a migration. `data` is never filtered or
  indexed.
- `attempt` and `node_id` of step entries keep coming from the job row, as today.

### 4.4 Actions

Every entry has an `action`; `state` is the resulting state of a job or step entry (null for
events and nodes). The public enums, all in `org.bikesched.history`:

```java
public sealed interface HistoryAction permits JobAction, StepAction, EventAction, NodeAction {}
```

| Enum | Entries | Actions |
|---|---|---|
| `JobAction` | job | `SCHEDULED` (new job, occurrence or child), `CLAIMED`, `SUCCEEDED`, `ATTEMPT_FAILED` (retry scheduled), `FAILED`, `SUSPENDED`, `RESUMED` (by an event, a child, a wake-up), `POSTPONED`, `RETRIED`, `CANCEL_REQUESTED`, `CANCELLED`, `RELEASED` (stopping node, dead node, unrecorded outcome), `MOVED` (to a changed recurrence), `DELETED` (with its recurring job), `FAILING`, `FAILURE_HANDLER_FAILED`, `COMPENSATING`, `COMPENSATED`, `COMPENSATION_FAILED` |
| `StepAction` | step | `STARTED`, `SUCCEEDED`, `FAILED`, `WAITING` (sleep, event, child, poll), `RECEIVED` (its event), `CHILD_STARTED`, `CHILD_ENDED`, `WOKE_UP`, `TIMED_OUT`, `POSTPONED`, `FAILING`, `RESOLVED` (by its failure handler), `PUBLISHED` (a publishing step), `CANCELLED`, `COMPENSATED`, `COMPENSATION_FAILED` |
| `EventAction` | event | `PUBLISHED` |
| `NodeAction` | node | `REGISTERED`, `STOPPED`, `REMOVED` (declared dead by another node's clean-up) |

- The lists are settled from the places recording history today (about 45 `Change` call sites);
  what used to be prose in `detail` becomes the action, the specifics go into `data`.
- Events: `PUBLISHED` with `data` `{"subjectName", "key", "retention", "received"}`, written by the
  same statement that records the receivers, so publishing gets no extra statement.
- Nodes: subject = node id (UUIDv7 already). `node_id` is the writer: the node itself for
  `REGISTERED` and `STOPPED`, the node whose clean-up removed it for `REMOVED`. A removed node that
  is still alive registers again under its own id (ADR 0042): `REGISTERED` again.

### 4.5 Actors

| `actor_type` | `actor_name` | written by |
|---|---|---|
| `NODE` | | a node by itself: claims, timeouts, releases, wake-ups, node entries |
| `APPLICATION` | | `Bikesched` API calls without a user |
| `JOB` | | a job handler: child jobs, `job.newEvent(…)`, its steps |
| `USER` | the user name | API calls with `actor(Actor.user(name))` |

- `node_id` stays separate: the node that wrote the entry, whoever the actor was ("simon cancelled
  via node-a").
- The user goes only on the entry of the action itself (the `PUBLISHED`, the `CANCEL_REQUESTED`,
  the `RETRIED`, the new job's `SCHEDULED`). Its consequences, written in the same transaction (the
  steps that received the event, the jobs it resumed), get the code path's actor type, no name,
  and `caused_by_id`. A publish reaching 500 waiting steps is one user entry plus 1000 caused ones.
- Only builders reached from `Bikesched` take `actor(…)`; the job-scoped ones (`job.newEvent`,
  child jobs) do not, so a handler cannot act as a user.
- The admin passes the signed-in user (`AccessGrant.user()`); its cancel message no longer
  carries `by simon`.

### 4.6 Cause links

`caused_by_id` holds the id of the event, job or node that caused the change, never the id of
another entry. The action says what it points to:

| Entry | `caused_by_id` |
|---|---|
| step `RECEIVED`, job `RESUMED` by an event | the event |
| child job `SCHEDULED` | the parent job |
| parent step `CHILD_ENDED`, parent job `RESUMED` by its child | the child job |
| job `RELEASED` from a dead node | the dead node |
| event `PUBLISHED` by `job.newEvent` | the publishing job |

A follow-up of the same subject (`CANCEL_REQUESTED`, then the node's `CANCELLED`) is not a caused
entry, it is the subject's next entry. An event's history is its own entries plus the entries
caused by it, so the receivers stay visible after the event and their jobs were deleted.

### 4.7 API

New exported package `org.bikesched.history`:

```java
public sealed interface HistoryEntry permits JobEntry, StepEntry, EventEntry, NodeEntry {
  UUID id();
  UUID subjectId();
  Instant createdAt();
  HistoryAction action();
  ActorType actorType();
  @Nullable String actorName();
  @Nullable UUID nodeId();
  @Nullable UUID causedById();
  @Nullable String data(); // the JSON object as text; core parses nothing
}

public record JobEntry(UUID id, UUID jobId, Instant createdAt, JobAction action, JobState state,
    int attempt, ActorType actorType, @Nullable String actorName, @Nullable UUID nodeId,
    @Nullable UUID causedById, @Nullable String data) implements HistoryEntry { … }
public record StepEntry(UUID id, UUID jobId, UUID stepId, Instant createdAt, StepAction action,
    StepState state, int attempt, …) implements HistoryEntry { … }
public record EventEntry(UUID id, UUID eventId, Instant createdAt, EventAction action, …)
    implements HistoryEntry { … }
public record NodeEntry(UUID id, UUID subjectId, Instant createdAt, NodeAction action, …)
    implements HistoryEntry { … }
```

- `@Nullable` rather than `Optional` on the interface: record components implement it.
- `nodeId()` is always the writing node. A node entry's subject is therefore `subjectId`, not
  `nodeId`: for a `REMOVED` entry the two differ (the removed node and the node that removed it).
- The records narrow `action()` to their enum. Step states need a public `StepState` (internal in
  `internal.database` today; the public one moves to `org.bikesched.job`, next to `JobState`).
- Also public: `Actor` (`Actor.user(String)`, validated like other names), `ActorType`,
  `SubjectType`, `HistoryQuery`.
- `Bikesched.findJobHistory(UUID)` returns `List<HistoryEntry>` (job and step entries);
  `findEventHistory(UUID)` returns the event's entries and those caused by it.
  `JobHistoryEntry` is removed.
- `Admin.findHistory(HistoryQuery)`: entries newest first, filtered by subject type, actor type,
  actions and a time bound, keyset-paged by (`created_at`, `id`).
- Builders, replacing plain methods (breaking, `feat!`):

  ```java
  boolean cancelled = bikesched.cancelJob(jobId).message("…").actor(actor).cancel();
  boolean retried = bikesched.retryJob(jobId).actor(actor).retry();
  boolean removed = bikesched.removeRecurringJob("report").actor(actor).remove(); // also TypedJob
  ```

  replace `cancel(UUID)`, `cancel(UUID, String)`, `retry(UUID)` and `removeRecurringJob(…)`.
  `JobBuilder` and `NodeEventBuilder` get `actor(Actor)`. Internally each builder builds an
  immutable command (sealed, like the job state of ADR 0043) executed in one place.
- Configuration: `history(Duration)` and `historyCleanUpInterval(Duration)` replace
  `jobHistory(…)`; Spring properties `bikesched.history-retention` and
  `bikesched.history-clean-up-interval`; Kotlin DSL `historyRetention`, `historyCleanUpInterval`.
  Kotlin uses the new builders as they are, `createJob` and `publishEvent` get an optional
  `actor`.

### 4.8 Admin UI

Module `bikesched-admin`. The layout below was reviewed as mockups (light, dark, the action
filter) with the design; they are not part of the repository.

- A History tab next to Jobs, Events and Nodes: one row per entry, newest first, with Time,
  Subject (type tag, name from `data.subjectName`, short id; a step's name below), Action, State
  after, Actor (a user's name, a causing job, or "node" and "application"), Node and Detail (the
  rendered `data`).
- Filters: Actor (everyone, users, application, jobs, nodes; Users uses the partial index),
  Subject type, Action (the enum of the selected subject type, several values), and a time bound.
  Filters other than Users and time are applied while walking the `created_at` index; a page stops
  after a bounded scan and offers "load more". No filter by name.
- A detail panel per entry with its fields and the entries its subject caused ("Caused by this
  event / job / node"), from the cause index.
- The Node column names the writing node from `bikesched_nodes`, and for a removed node from its
  own `REGISTERED` entry (one primary-key lookup per distinct node on the page). Only a node whose
  `REGISTERED` entry is past the retention shows its short id.
- Live refresh re-reads a trailing window (the last minute before the newest shown entry) and
  merges by `id`: an entry of a long transaction commits after newer ones were shown. An entry of
  a transaction open longer than the window (a caller's connection held for minutes) is missed by
  live refresh and shows up on the next Reload, which reads the page from scratch; the docs say so.
- Job, step, event and node pages show the action next to the state badge.
- Visible to viewers too, user names and messages included.
- JSON API: `GET api/history` (query parameters for the filters and the cursor), the `history` of
  `api/jobs/{id}`, `api/steps/{id}`, `api/events/{id}` and `api/nodes/{id}` in the new shape.
  `data` is parsed with the admin's JSON library and passed on as an object.
- The admin's writes pass `Actor.user(grant.user())` when the access grant names a user.
- The tab says when the history is disabled on the serving instance.

## 5. Rejected alternatives

- **Job history plus a separate event table**: less churn, but two tables, two clean-ups, a
  `union all` for the feed, and a table per future subject.
- **A separate audit table written by the admin**: a second write path and transaction, and the
  same fact stored twice.
- **Database triggers**: rejected in ADR 0007; they could not know the user either.
- **`bikesched.actingAs(Actor)`, a `Bikesched` view per user**: wraps the API per admin request
  for a handful of writes.
- **A thread-local actor scope**: implicit, lost on executor hops, leaks into job threads;
  `ScopedValue` is not in Java 21.
- **Overloads with an actor** (`cancel(id, message, actor)`): every option multiplies the
  overloads.
- **One `updateJob(id)` builder for all actions**: every option next to every verb.
- **Ordering by `id` alone**: node clocks differ; two changes of one job on two nodes milliseconds
  apart could sort wrong.
- **A stored per-job sequence**: needs the job row lock on every entry, or an advisory lock per job
  (one lock per resumed job in a publish, `max_locks_per_transaction`, a fixed lock order against
  deadlocks), for a number `row_number()` computes anyway.
- **`(subject_type, subject_id, id)` as primary key**: ids are unique across types; every lookup
  and `caused_by_id` would need the type.
- **`(created_at, id)` indexes**: twice the entry size for a tie-break that is rare.
- **One `actor_id` for a user name or a node id**: `text` instead of `uuid` on most rows, a user's
  action would lose the node, an unstarted instance has no node id.
- **`ADMIN` as an origin**: mixes the code path with who acted; a user is a user, whether through
  the admin or other tooling.
- **The user on the consequences of an action**: one publish would show as hundreds of user
  entries.
- **Typed columns for names and specifics, or text `detail`**: names as columns are smaller
  (156 instead of 185 bytes per row) but each new specific would need a column or prose; `data`
  keeps them structured for the UI without migrations.
- **Links in `data`**: `caused_by_id` and `step_id` need indexes and lookups; as JSON they would be
  text UUIDs in expression indexes.
- **Recording user entries without the history setting**: the history stays optional.

## 6. Testing

- `SchemaMigratorTest`: `bikesched_history` instead of `bikesched_job_history`.
- Store level (`AbstractStoreTest`): every CHECK rejects its invalid combination (actor name without
  a user and the reverse, step id on an event, an action of another type, a non-object `data`);
  `record` builds `data` without null fields and `null` without specifics; names survive deleting
  the job, step, event and node rows; texts are cut to 4000 characters.
- Ordering: entries of one statement in their order; a change committed after another sorts after
  it; keyset paging across equal `created_at` values returns every row once.
- One test per write path recording each action at least once (like `DueJobsWritePathsTest`),
  checking action, state, actor type and `caused_by_id`: claims, attempts, suspensions, resumes by
  event, child and wake-up, postponing, retry, cancel request and cancellation, releases by a
  stopping and a dead node, moved and deleted occurrences, failure handlers, compensation, publish
  from the application, from a job and by a user, node registration, stop and removal.
- Actors: a user on `cancelJob`, `retryJob`, `removeRecurringJob`, `newJob` and `newEvent` lands
  on the direct entry only; the consequences of a user's publish carry `APPLICATION` and the
  event as cause; job-scoped builders offer no `actor`.
- `findJobHistory` returns `JobEntry` and `StepEntry` in order; `findEventHistory` the publish and
  its receivers, also after the event and the jobs were deleted.
- Disabled history: nothing recorded, user entries included; no transaction added.
- Clean-up deletes in batches by `created_at` (existing tests, new table).
- Admin: `api/history` filters, cursor and trailing window; the writes pass the signed-in user;
  history in the job, step, event and node responses; the tab renders (existing UI test setup).
- Spring and Kotlin: renamed settings.

## 7. Implementation steps

Four pull requests, each green on its own:

1. **Table and entries**: `bikesched_history` in `V001`, `HistoryStore` with the new statement,
   the action enums and the sealed entries, actor types `NODE`, `APPLICATION` and `JOB`, every
   `Change` call site naming its action and specifics, `findJobHistory` on the new types,
   `history(…)` settings (core, Spring, Kotlin), the admin's job and step history on the new
   shape. Removes `bikesched_job_history` and `JobHistoryEntry`.
2. **Events, nodes and causes**: `PUBLISHED` and node entries, `caused_by_id` on every caused
   entry, `findEventHistory`, the event and node pages in the admin.
3. **Users**: `Actor`, `actor(…)` on `JobBuilder` and `NodeEventBuilder`, the `cancelJob`,
   `retryJob` and `removeRecurringJob` builders replacing the methods (examples, docs, Kotlin,
   Spring), the admin passing the signed-in user.
4. **History tab**: `Admin.findHistory`, `api/history`, the tab, its filters and detail panel.

Docs in the same changes: the ADR (superseding ADR 0007 and the history part of ADR 0057),
`jobs/README.md#job-history` (renamed to the history), `getting-started/configuration.md`,
`integrations/spring.md` and `kotlin.md`, `operations/database.md` (table list, sizes),
`operations/admin.md` (API table, the tab), `module-info.java` and `ArchitectureTest` for the new
package, `AGENTS.md` (history conventions under Database).

## 8. Open questions

None.

## 9. Decisions

1. One table `bikesched_history` for jobs, steps, events and nodes (4.1).
2. Idempotency duplicates and no-ops are not recorded (3).
3. A builder per write action with an `actor(…)` option; `cancelJob(id)…cancel()`,
   `retryJob(id)…retry()`, `removeRecurringJob(name)…remove()` (4.7).
4. A History tab with every entry (4.8).
5. UUIDv7 ids, order by (`created_at` from `clock_timestamp()`, `id`), no stored sequence and no
   lock (4.2).
6. `subject_type` as a column, not in the primary key `(subject_id, id)` (4.1).
7. `action`, an enum per subject type and for steps, sealed `HistoryAction` (4.4).
8. Nodes are a subject, keyed by their UUIDv7 id (4.4).
9. `step_id` as a column, the step's name in `data` (4.1, 4.3).
10. `caused_by_id` references a subject, never an entry, with a partial index (4.6).
11. A sealed `HistoryEntry` with a record per subject (4.7).
12. `created_at` alone in the indexes (4.2).
13. `actor_type` and `actor_name` (users only), `node_id` stays the writer (4.5).
14. The user only on the direct entry; consequences are linked by `caused_by_id` (4.5).
15. `data jsonb` for names and specifics, built in SQL, returned by core as JSON text, parsed by
    the admin (4.3, 4.7).
16. The history stays completely optional, per node (3).
17. The admin's live refresh re-reads a trailing window (4.8).
18. Viewers see user names and messages (4.8).
19. A separate retention for user entries is deferred (`IDEAS.md`).

## 10. Decided while implementing

1. `node_id` is the node writing the entry, set once the node registered (`HistoryStore.writtenBy`),
   not the node in the job row: the application's calls through a node carry it too, an instance
   that does not poll (and so never registers) records none. This replaces "`attempt` and
   `node_id` of step entries keep coming from the job row" (4.3); `attempt` still does.
2. A poll records `WAITING` (with `checks`: 1) after its first check, then only its outcome
   (`SUCCEEDED`, `FAILED` or `TIMED_OUT` with `checks`); further claims and suspensions are left
   out as before.
3. The `data` keys: `subjectName`, `stepName`, `error`, `message`, `reason`, `run`, `checks`,
   `dueAt`, `waitUntil`, `recurrence`, `previousRecurrence`, `idempotencyKey`, `reference`,
   `childJobId`, `childState`, `eventId`, `eventName`, `eventKey`. Each action's Javadoc lists its
   keys. `reason` is a constant: `FAILURE_HANDLER`, `COMPENSATION` or `CANCELLATION` on a
   `CLAIMED`, `NODE_STOPPED`, `NODE_DEAD` or `OUTCOME_NOT_RECORDED` on a `RELEASED`,
   `NO_FAILURE_HANDLER`, `FAILURE_HANDLER_RAN` or `FAILURE_HANDLER_GAVE_UP` on the end of a
   failing job or step.
4. Step names are joined from `bikesched_steps` by `step_id`; a caller passes the name only where
   the step row is not written in the transaction (a job's `SUSPENDED` and `POSTPONED` name their
   step).
5. Users (step 3), revised: the plain `cancel`, `retry` and `removeRecurringJob` methods of
   `Bikesched` stay and record `APPLICATION`; the builders with `actor(…)` live under
   `bikesched.admin()`, so the change is not breaking. `JobBuilder` and `NodeEventBuilder` get
   `actor(…)` as planned.
