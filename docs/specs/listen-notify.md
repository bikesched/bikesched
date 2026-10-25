# Specification: waking pollers with `LISTEN/NOTIFY`

Status: **implemented** ([ADR 0032](../decisions/0032-batched-notifications-after-commit.md), user
docs: [Due jobs start right away](../getting-started/configuration.md#due-jobs-start-right-away));
decisions in §9, follow-ups in §10.

## 1. Goal

A job starts when it is due, not on the node's next poll. Today all due work waits up to one
`pollInterval` (default 5 seconds): a job scheduled "now", a job resumed by an event or a child
job, a retry, a sleep that ended, a poll check, a wait deadline, the next occurrence of a
recurring job. Faster reactions cost a shorter interval, i.e. more claim queries, also while idle.

With this feature, a node that makes jobs due now tells every node through PostgreSQL's
`LISTEN/NOTIFY`, and each node keeps a timer for the delayed work of its job types. Polling on the
interval stays as a safety net, so its default goes up to 10 seconds (§4.6):

```java
Bikesched bikesched =
    Bikesched.builder(dataSource)
        .build(); // listens for due jobs by default
```

This is a first version; parts that only help in edge cases are follow-ups (§10).

## 2. Today

`JobPoller` claims due jobs every `pollInterval` on the `bikesched-poller` thread and polls again
immediately only while a poll fills its batch of 10. A job is claimable when `state in
('SCHEDULED', 'WAITING', 'COMPENSATING') and scheduled_at <= now()`. These statements make jobs
claimable:

| Where | Statement | Due |
|---|---|---|
| `schedule()`, also on the caller's connection and from steps | `JobStore.INSERT_JOB_SQL`, `INSERT_CHILD_JOB_SQL` | now or later |
| Occurrence of a recurring job | `INSERT_OCCURRENCE_SQL`, `RESCHEDULE_OCCURRENCE_SQL` | usually later |
| Event published to waiting steps | `EventStore.RESUME_WAITING_STEPS_SQL` | now |
| Child job ended | `JobStore.RESUME_PARENT_SQL` | now |
| `retry(jobId)`, `cancel(jobId)` | `RETRY_JOB_SQL`, `REQUEST_CANCEL_SQL` | now |
| Failed attempt, compensation | `FAIL_JOB_SQL`, `CANCEL_JOB_SQL`, `FAIL_COMPENSATION_SQL` | now, or later with a retry delay |
| Suspension (event wait, sleep, poll check, child job) | `StepStore.SUSPEND_JOB_SQL`, `suspendAtPoll` | deadline or wake-up time; `infinity` without one; now if the step completed meanwhile |
| Jobs of dead nodes, inactive claims, `close()` | the three `RELEASE_*_SQL` | now |

## 3. Non-goals

- Replacing polling: the interval poll catches what notifications miss.
- Delivering jobs through notifications: the claim stays the `SKIP LOCKED` update, keeping priority
  order and capacity checks.
- Notifying due-later work (alternative C, §6) or anything other than due jobs.
- Notifying writes that bypass bikesched's API (plain SQL): they start on the next poll, as today.
- Claim reports (§6): later, if measurements with many nodes per type show the need (`IDEAS.md`).

## 4. Design

### 4.1 Concept

A node polls when one of four things happens:

| Wake-up | Source | Covers |
|---|---|---|
| Notification "due now" | the node that made jobs due now, batched after the commit (§4.2) | new jobs, events, child jobs ending, `retry`, `cancel`, released jobs |
| Own due-later write | the writing node arms its timer in-process (§4.4) | its jobs' suspensions, retries with backoff, next occurrences, delayed jobs it scheduled |
| Next-due query | the node asks for its types' earliest due time within the next interval | due times it didn't write (restart, reconnect, delayed jobs scheduled elsewhere) |
| Interval poll | `pollInterval` | safety net |

Each node keeps **one timer** with the earliest due time it knows of; later due times are dropped,
so memory doesn't grow with delayed jobs. The next-due query recovers what the timer doesn't know.
Most due-later writes happen on a node that handles the type; only a delayed `schedule()` from
elsewhere (another application, the caller's connection, a node without the handler) waits for a
next-due query, i.e. up to one `pollInterval`.

### 4.2 Notifying: batched after the commit

Only a transaction that issues `NOTIFY` takes PostgreSQL's commit lock (§5.1), so bikesched never
notifies inside a job write. The stores report what their statements make due on their `Sql`
handle (`db.dueNow(type)`, `db.dueAt(type, time)`, `db.due(type, time)` for inserts, which picks
one by the due time); `DatabaseAccess` passes the reports to the node's `DueJobs` (interface in
`internal.database`) once the work committed, and drops them on a rollback. `DueJobsNotifier`
(`internal.job`) implements it and collects the types of jobs made due now:

- After the commit, `dueNow(types)` wakes the node's own poller right away (§9.16) and adds the
  types to a concurrent set.
- The first type added to an empty set schedules one flush after `notifyInterval` (default 100 ms,
  `bikesched-notifier` thread). The flush sends the set in one auto-commit statement
  (`JobStore.notifyDue(types)`):

  ```sql
  select pg_notify('bikesched_jobs', t) from unnest(?::text[]) t
  ```

  Checked on `postgres:18`: `'{a,b c,a}'` sends `a` and `b c`.
- At most one notifying commit per node and `notifyInterval` (10/s by default), whatever the write
  rate; an idle node sends nothing. A failed flush logs a `WARN` and drops the batch (the interval
  poll catches it); `close()` flushes once more.
- **Writes on the caller's connection** can't be flushed after a commit bikesched doesn't see; they
  issue `pg_notify` inside the caller's transaction (`notifyDue(connection, types)`), so it is sent
  on commit and not on rollback.

Write paths that report types (each with `db.dueNow` or `db.due` next to its statement):

| Write path | Store call | Type from |
|---|---|---|
| `schedule()` due now (own or caller's connection), child jobs | `insertJob`, `startChildJob` | `NewJob.type()` when due now |
| event published (API, caller's connection, step) | `publishEvent`, `publishEventInTransaction`, `publishEventAsStep` | jobs resumed by `RESUME_WAITING_STEPS_SQL` (`returning j.type` in its `resumed` CTE) |
| child job ended | completion paths with `RESUME_PARENT_SQL` | parent (`returning type`) |
| `retry(jobId)`, `cancel(jobId)` of a scheduled or waiting job | `retryJob`, `requestCancel` | `returning type` |
| releases (`close()`, start, inactive claims, dead nodes) | the three `release*` methods | released rows |
| recurring occurrence already due | `registerRecurringJob`, `insertOccurrence` | `NewJob.type()` when due now |

Not notified: writes that leave the writing attempt's own job due now (retry without backoff,
compensation start, suspension whose step completed meanwhile). Their stores report `dueAt` with a
time that has passed, which makes the node poll itself. A node also receives its own
notifications. `NodeCleanUp` needs nothing: its release statement reports in the store.

### 4.3 Listening

`JobNotificationListener` (`internal.job.claim`, thread `bikesched-listener`), started and closed
by `JobPoller`:

- Borrows **one connection** through `JobStore.listen()`, which returns a `DueJobsListening`
  (`internal.database`, so the JDBC stays in the database package): it runs `listen
  bikesched_jobs` and wraps `connection.unwrap(PGConnection.class).getNotifications(timeoutMillis)`
  (pgjdbc 42.7.13). A payload in `registry.types()` calls `pollNow()`; others are ignored.
- Connects on its own thread with the pool's timeout, so `start()` never waits. On failure: `WARN`,
  wait `pollInterval`, reconnect. After each `listen` it wakes the poller once (with the next-due
  query) for what was missed.
- **Self-check:** after each `listen` it sends itself a random token and waits up to 1 s; if
  nothing arrives (e.g. PgBouncer in transaction mode) it logs a `WARN`.
- `close()` aborts the connection (`Connection.abort`), which ends the blocking wait.
- Connections that don't unwrap to `PGConnection` get one `WARN`; the node works without
  notifications.

Core gets `requires org.postgresql.jdbc;` (automatic module; `requires-automatic` lint suppressed as
in `bikesched-micrometer`).

### 4.4 The wake-up timer

`JobPoller` has two entry points: `pollNow()` and `wakeAt(type, dueAt)`.

- **One thread.** Timer state lives on the `bikesched-poller` thread: `wakeAt` submits `arm(dueAt)`
  to the poller executor, which also runs the timer and every poll. No wake-up can be lost between
  check and write. `pollNow()` merges bursts with one `AtomicBoolean`.
- `arm(dueAt)` replaces the timer only if `dueAt` is earlier. When the timer fires, it polls and
  then runs the next-due query.
- **Own writes arm it** through `DueTimes.dueAt(type, dueAt)`, a callback in `internal.job` that
  `JobPoller` implements (so `internal.job` doesn't depend on `claim`), called by the
  `DueJobsNotifier` with the due time the store reported after the commit. Due times beyond the
  next interval poll are dropped (its next-due query finds them):

  | Write | Due time from |
  |---|---|
  | suspension with a deadline or wake-up time | `SuspendedJob.waitUntil()` when waiting |
  | retry or compensation with a delay | `FailedJob.scheduledAt()` |
  | next occurrence, first occurrence | `NewJob.scheduledAt()` when inserted |
  | delayed `schedule()`, child jobs | `NewJob.scheduledAt()` |

- **Next-due query** (`JobStore.nextDueIn(types, window)`) with start/reconnect, timer polls,
  interval polls and handler registrations after `start()`; not with "due now" polls, whose timer
  is still valid. It runs right **before** the claim: the query sees jobs due after its `now()`,
  the claim those due before its own, so no job becomes due unseen in between (run after the
  claim, jobs due between the two statements were only found by a later poll; the concurrency
  test caught it). The window is `pollInterval`, because the next interval poll looks again:

  ```sql
  select ceil(extract(epoch from min(scheduled_at) - now()) * 1000)::bigint
    from bikesched_jobs
   where state in ('SCHEDULED', 'WAITING', 'COMPENSATING')
     and scheduled_at > now() and scheduled_at <= now() + ? and type = any(?)
  ```

  The database computes the delay, so node clocks don't matter. A delay is at least 10 ms.

Next-due query, measured on `postgres:18` with 200k finished, 50k waiting, 100k delayed jobs over
30 days, 20 types; only the existing poll index:

| Query | 4 priorities | 50 priorities |
|---|---|---|
| unbounded `min(scheduled_at)` | 16.3 ms | 16.1 ms |
| unbounded, one lookup per priority | 4.4 ms | 13.0 ms |
| unbounded, extra index `(type, scheduled_at)` | 0.08 ms | 0.08 ms |
| **bounded to 30 s** | **0.53 ms** | **0.89 ms** |

Bounded, it is an index-only scan whose cost depends on jobs due within the window, not on jobs
sleeping for days; no extra index to maintain on every job write.

### 4.5 Capacity

A wake-up that found no free slot sets `wakeWhenSlotFrees`; the next `JobSlot.release()` clears it
and calls `pollNow()`. Without it, a released slot doesn't poll, as today.

### 4.6 Configuration

| Option | Default | Meaning |
|---|---|---|
| `listenForDueJobs(boolean)` | `true` | `false`: neither listens nor notifies (no extra connection); timer and next-due query still run |
| `notifyInterval(Duration)` | 100 ms | batching window of the notifier: extra latency for due-now work on other nodes, and 1 / max notifying commits per second |
| `pollInterval(Duration)` (changed) | 10 s (was 5) | safety net, next-due window, latency of delayed jobs scheduled elsewhere and of due-now work on nodes that don't listen |

- Added to `Settings`, Kotlin DSL, `docs/configuration.md`, Spring properties
  (`bikesched.listen-for-due-jobs`, `bikesched.notify-interval`). No schema change.
- Set `listenForDueJobs` the same on all nodes and clients: writes of a node that doesn't notify
  reach others only on their interval poll. Clients with `autoStartPolling(false)` notify but
  don't listen.
- Nodes that don't listen should lower `pollInterval` (docs recommend 5 s or less).

### 4.7 Logs

- `INFO`: listening started, reconnected.
- `WARN`: listening failed (with retry delay), self-check failed, no `PGConnection`, flush failed
  (once per failure streak).
- `DEBUG`: notifications received, flushes sent, timer polls.

### 4.8 Package: `internal.job.claim`

The poller, the listener and the timer move into `org.bikesched.internal.job.claim`:

| Class | Package | Lines |
|---|---|---|
| `JobPoller`, `JobPollerSettings` | moved to `claim` | 249 + 45 before |
| `JobNotificationListener` | new, `claim` | ~250 |
| `WakeUpTimer` | new, `claim` | ~65 |
| `DueTimes`, `DueJobsNotifier` | new, `internal.job` | ~30, ~150 |
| `DueJobs`, `DueJobsListening` | new, `internal.database` | ~55, ~110 |

Nothing in `internal.job` uses `JobPoller` (checked), so the dependency is one-way. The ArchUnit
"Job" layer becomes `org.bikesched.internal.job` without sub-packages (used by Node and Claim), and
a new "Claim" layer `internal.job.claim..` is used by Node only. The `listen` statement stays in
the database package (`DueJobsListening`). Logger names change; two tests get new imports. Not
named `poll` (poll steps) or `scheduling` (`JobScheduler`).

## 5. Issues

| Issue | Impact | Mitigation |
|---|---|---|
| Notifying commits are serialized by an instance-wide lock held through the WAL flush (`async.c`, `PreCommit_Notify`; still on PostgreSQL 19 and `master`, §5.1) | ~5,000 notifying commits/s on fast local storage, estimated 500–2,000 on network disks | job writes never notify; the notifier sends ≤ 10/s per node |
| Batching window | ≤ 100 ms extra for due-now work on other nodes | own follow-up work uses `pollNow()`; `notifyInterval` configurable |
| Notification lost (node dies between commit and flush, flush fails) | job starts on the next interval poll (≤ 10 s) | interval poll |
| A write path forgets to report types | that path is up to 10 s slower, silently | guard test over all store statements (§7), `AGENTS.md` rule |
| Delayed jobs scheduled elsewhere | start ≤ 10 s late | next-due query on interval polls |
| Every node gets every notification; all nodes of a type poll at once | empty claims, ~1 ms each | type filter; jitter or claim reports later |
| One pool connection per listening node for its lifetime | pool needs one more connection; leak detectors may log it | docs: size the pool +1 |
| PgBouncer in transaction mode drops `LISTEN` | due-now work waits for the interval | self-check `WARN`; docs: turn off listening, lower interval |
| Notification queue full (8 GB) blocks notifying commits | flushes fail | one short notification per type and flush; docs mention `pg_notification_queue_usage()` |
| Several bikesched schemas in one database share the channel | an extra empty poll | channel per schema if it ever matters |

### 5.1 Benchmark: the commit lock

`pgbench`, 8 s per run, PostgreSQL 18.6 in Docker (10 CPUs, local SSD, 114 µs per WAL flush),
`synchronous_commit = on`, one listener. Single-statement job writes, notifications from a trigger
(the rejected designs) or from a separate connection (the notifier):

| tps | 1 client | 8 | 32 | 64 |
|---|---|---|---|---|
| insert, no notification | 3,794 | 15,605 | 30,999 | 35,949 |
| insert, notifying in the transaction | 3,780 | 5,050 | 4,783 | 4,599 |
| suspension, notifying (alternative C) | 3,392 | 4,835 | 4,860 | 4,661 |
| suspension, not notifying | 3,526 | 15,102 | 30,263 | 35,247 |
| PostgreSQL 19beta4: insert without / with notifying | 3,721 / 3,542 | 15,558 / 4,314 | 33,343 / 3,737 | 37,962 / 4,095 |

- Notifying commits cap at ~5,000/s regardless of clients; latency rises from ~2,000/s (127 ms
  average at 4,000/s). Writers that don't notify are not slowed.
- The lock is held through the WAL flush (~25,000/s with `synchronous_commit = off`), so slower
  disks lower the cap. Without any listener the cap is the same.
- PostgreSQL 19's `LISTEN/NOTIFY` optimisation (commit 282b1cd, waking only interested listeners)
  doesn't change the cap.
- **32 clients writing without notifying plus one connection sending 100 notifications/s: 28,835
  tps.** Separate, batched notifications cost nothing measurable, hence the notifier.

## 6. Alternatives

| Alternative | Why not |
|---|---|
| A. Notify due now only, no timer (first draft) | delayed work keeps the interval's latency |
| C. Also notify due-later work (`"<delay ms> <type>"`) | every suspension notifies and hits the commit-lock cap (§5.1) |
| D. Timer and next-due query only, no `LISTEN` | due-now work waits for the interval; this is `listenForDueJobs(false)` |
| E. One elected node keeps the timer | leader election for a sub-millisecond query |
| Trigger on `bikesched_jobs` | atomic and covers every path, but every due-now write takes the commit lock (capped at ~5,000/s), also when nobody listens; switching it off needs a database setting (new connections only) or runtime DDL |
| `pg_notify` in each writing statement | same lock as the trigger |
| Notifying after each commit, unbatched | one notifying commit per due-now write, same cap |
| Queue of all known due times instead of the next-due query | incomplete (only own writes), empty after restart, stale; the query is needed anyway |
| Claim reports (nodes announce claims so others skip) | a notifying commit per woken poll, extra wait for everyone; out of scope (§3) |
| Node id in the payload | saves only the sender's duplicate poll; own work already uses `pollNow()` |
| Job id as payload, claim by id | bypasses priority order, races with polling |
| `pg_cron` | an extension bikesched would require |

## 7. Testing

`JobNotificationTest` (core) with a 1-minute poll interval, so only notifications and the timer can
start jobs in time (listener cases in `JobNotificationListenerTest`):

- Due now: scheduled, event resuming a waiting job, child job ending, `retry`, `cancel` of a
  waiting job, jobs released by `close()` of another node: each starts within a second.
- Due later: a job scheduled in 2 s by the handling node, a 2-second sleep, poll check, backoff,
  event wait deadline and recurring occurrence each run within a second of their due time. Scheduled
  by a node without the handler, it runs by the next interval poll (interval 3 s in that test).
- Caller's connection: job and event start after commit, never after rollback.
- Batching: 1,000 jobs in a burst send only a few notifications and all run; with
  `notifyInterval(500 ms)` another node starts the job after about 500 ms; an idle node sends
  nothing; a failing flush logs the `WARN` and later flushes work.
- A node started after a job went to sleep runs it on time; a handler registered after `start()`
  runs its due and delayed jobs on time.
- Capacity: with one slot, a job scheduled while another runs starts as soon as the slot frees.
- No lost wake-ups under concurrent `wakeAt`/`pollNow` while the timer fires.
- Listener: reconnect after `pg_terminate_backend`, startup with an exhausted pool (`start()`
  returns promptly), self-check failure (wrapper running `unlisten *`), foreign types ignored,
  prompt `close()`, `listenForDueJobs(false)`.

Store tests (`DueJobsStoreTest`, with a recording `DueJobs`): each store call of §4.2 reports the
types it made due now (none for delayed jobs, suspensions, claims, rolled-back writes) and due-later
writes their due time; `notifyDue` reaches a plain JDBC listener on its own connection and in a
caller's transaction; `nextDueIn` inside and beyond the window; closing a listening ends a blocked
receive.

**Guard test** (`DueJobsWritePathsTest`, no database): reads the stores' `*_SQL` constants by
reflection and keeps those containing `insert into bikesched_jobs` or `update bikesched_jobs`
(keyed by class). Each must be listed as **notifies** or **silent** (with a reason); an unlisted or
stale name fails with "classify <NAME> as notifying or silent, see §4.2". Today, 18 statements:

| List | Statements |
|---|---|
| notifies | `INSERT_JOB_SQL`, `INSERT_CHILD_JOB_SQL`, `INSERT_OCCURRENCE_SQL`, `RESUME_WAITING_STEPS_SQL`, `RESUME_PARENT_SQL`, `RETRY_JOB_SQL`, `REQUEST_CANCEL_SQL`, the three `RELEASE_*` |
| silent: job runs or ends | `CLAIM_JOBS_SQL`, `COMPLETE_JOB_SQL`, `COMPLETE_COMPENSATION_SQL` |
| silent: attempt polls itself | `FAIL_JOB_SQL`, `CANCEL_JOB_SQL`, `FAIL_COMPENSATION_SQL`, `SUSPEND_JOB_SQL` |
| silent: moves a pending occurrence | `RESCHEDULE_OCCURRENCE_SQL` |

It doesn't see SQL built at runtime or outside bikesched. Kotlin DSL and starter property tests get
one line each; no schema test changes.

## 8. Implementation steps

0. Move `JobPoller`/`JobPollerSettings` to `internal.job.claim` with `package-info.java`, ArchUnit
   layers and `AGENTS.md` layout line; no behaviour change, own commit.
1. Stores: `returning type` (§4.2), `notifyDue` (both variants), `nextDueIn`, store tests.
2. `DueJobsNotifier` on every write path, `pollNow()` after an attempt left its job due now, guard
   test.
3. `JobPoller`: `pollNow`, `wakeAt`, timer on the poller thread, `DueTimes`, next-due query,
   capacity wake-up, wake-up on registration; tests with listening off.
4. `JobNotificationListener` (own-thread startup, reconnects, self-check), `module-info.java`.
5. Options `listenForDueJobs`, `notifyInterval`, `pollInterval` default 10 s; Kotlin DSL, starter;
   docs (`configuration.md`, `jobs.md`, `steps.md`, `spring.md`: pool sizing, PgBouncer, caller's
   connection, latency); `AGENTS.md` rule that write paths making jobs due now report their types.
6. ADR, `IDEAS.md` (remove entry, extend "Connection pool guidance"), spec status, index.

## 9. Decisions

1. Listening and notifying **on by default**; one connection per node.
2. **bikesched notifies itself, batched after the commit** (§4.2), not via a trigger: job writes
   stay outside the commit lock, no schema change. Caller's-connection writes notify in the
   caller's transaction. Channel `bikesched_jobs`.
3. **No separate data source** for listening yet.
4. **Wake-ups at capacity are remembered** (§4.5).
5. Names: `listenForDueJobs`, `notifyInterval`, `JobNotificationListener`, `DueJobsNotifier`,
   `DueTimes`; threads `bikesched-listener`, `bikesched-notifier`.
6. **Only due-now work is notified**; own due-later writes arm the node's timer, the next-due query
   covers the rest (alternative B). Delayed jobs scheduled elsewhere start up to one interval late.
7. **No jitter** in v1.
8. **`pollInterval` default 10 s, fixed**; the self-check only logs.
9. **Handlers registered after `start()`** wake the poller with the next-due query.
10. **Package `internal.job.claim`** with its own ArchUnit layer, moved first.
11. **Claim reports out of scope** (`IDEAS.md`).
12. **Next-due query bounded to `pollInterval`** on the existing poll index; no extra index; a store
    method of its own.
13. **Timer confined to the poller thread.**
14. **Listener connects on its own thread**; `start()` never waits.
15. **`notifyInterval` default 100 ms**, window started by the first type, so idle nodes don't tick.

Decided while implementing:

16. **The stores report** what they make due (`Sql.dueNow/dueAt/due`), and `DatabaseAccess`
    passes it on after the commit, instead of store calls returning types for every caller to
    forward: the report sits next to each statement, no store signature changes, `NodeCleanUp`
    needs no `Consumer`, and `DueTimes` is reached through the `DueJobs` sink.
17. **Own due-now writes wake the own poller right away**, not ≤ 100 ms later by the own
    notification; costs at most one empty claim and makes nodes with `listenForDueJobs(false)`
    react to their own writes.
18. **The next-due query runs before the claim** (§4.4), not after it.
19. **A released slot returns its permit before it polls** (`JobSlot`), so the woken poll sees it.

## 10. Follow-ups

- **Adaptive `pollInterval`**: 30 s while the self-check confirms listening, 5 s otherwise; a set
  value always wins. Needs "unset" vs. "5 s" in the builder, rescheduling on switch, startup log.
- **Next-due query inside the claim statement** (a CTE, checked on `postgres:18`): saves a round
  trip after timer and interval polls, at the price of a more complex claim.
- **Claiming `limit + 1`** with `for update skip locked` on all candidates, returning `more`
  (checked: 2/`more`, 2/`more`, 1, 0 for 5 due jobs and limit 2): saves an empty poll and lets a
  full node poll as soon as a slot frees. Locking only the first `limit` of unlocked candidates
  claims less under contention (`{2}` instead of `{2,3}`), so not that variant.
- **Jitter** of 0–50 ms before a woken poll, if many nodes per type collide.
