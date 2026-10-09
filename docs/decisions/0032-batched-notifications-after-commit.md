---
title: "32. Waking pollers with batched notifications after the commit"
---

# 32. Waking pollers with batched notifications after the commit

Date: 2026-10-08

## Status

Accepted

## Context

A due job waited for the next poll of a node: up to one `pollInterval` (5 seconds by default) for
a new job, a job resumed by an event or a child job, a retry, an ended sleep, a poll check, a wait
deadline or the next occurrence of a recurring job. Reacting faster meant a shorter interval, i.e.
more claim queries on every node, also while idle.

PostgreSQL's `LISTEN/NOTIFY` can wake the nodes, but a transaction that issues `NOTIFY` takes a
database-wide lock at commit that is held through the WAL flush (`async.c`, `PreCommit_Notify`;
unchanged in PostgreSQL 19). Measured with pgbench on PostgreSQL 18: notifying commits cap at about
5,000 per second whatever the number of clients (writes that don't notify reach 30,000–36,000),
while one connection sending 100 notifications per second next to them costs nothing measurable.

Alternatives considered (details and measurements in the
[spec](../specs/listen-notify.md#6-alternatives)): a trigger on `bikesched_jobs` (atomic and
complete, but every due-now write takes the commit lock, also when nobody listens, and switching it
off needs a database setting or runtime DDL); `pg_notify` in each writing statement or right after
each commit (the same lock per write); notifying due-later work as well (every suspension would
take the lock); timer and next-due query only, without `LISTEN` (due-now work keeps the interval's
latency); one elected node keeping the timer; an in-memory queue of all known due times instead of
the next-due query (incomplete by construction); the job id or node id as payload; `pg_cron`.

## Decision

bikesched notifies itself, batched after the commit, and only about work due now:

- The stores report the jobs their writes make due on their `Sql` handle (`dueNow(type)`,
  `dueAt(type, time)`); `DatabaseAccess` passes them to the node's `DueJobs` after the commit, so
  rolled-back writes report nothing. A guard test lists every store statement that inserts or
  updates jobs as notifying or silent.
- `DueJobsNotifier` wakes the node's own poller right away and collects the types made due now;
  the first type of an empty batch schedules one flush after `notifyInterval` (100 ms), which sends
  `select pg_notify('bikesched_jobs', t) from unnest(?::text[]) t` in an auto-commit statement of
  its own. A node sends at most one notifying commit per interval; an idle node sends none.
- Writes on the caller's connection notify inside the caller's transaction instead, since
  bikesched does not see that commit: sent on commit, not on rollback.
- Due-later times written by a node (suspensions, retries with a delay, compensation, occurrences,
  delayed jobs) only arm that node's one wake-up timer, kept on the poller thread. Before every
  interval and timer poll, a bounded next-due query (`scheduled_at` within the next poll interval,
  on the existing poll index) arms it for due times the node did not write.
- `JobNotificationListener` holds one connection per node, borrowed on its own thread, and wakes
  the poller for notifications of the types the node handles. It reconnects after failures and
  checks after each connect that its own notification arrives (warning only).
- A wake-up that finds no free slot makes the next released slot poll.
- `listenForDueJobs(true)` by default; `pollInterval` goes up to 10 seconds as the safety net.

## Consequences

- Due-now work starts within about `notifyInterval` on other nodes, own work right away, delayed
  work of the node when due; delayed jobs scheduled by another node (or on a caller's connection)
  start up to one `pollInterval` late; writes by plain SQL start on the next interval poll.
- Job writes never take the notify commit lock. A lost notification (node dies between commit and
  flush, failed flush) costs up to one `pollInterval`.
- Every write path that makes jobs due now must report them: a forgotten path is silently up to one
  interval slower. The guard test and an `AGENTS.md` rule cover the stores; SQL built at runtime is
  not covered.
- Each listening node holds one pool connection; PgBouncer in transaction mode silently drops
  `LISTEN` (the self-check warns), so such setups turn listening off and lower the interval.
- Core requires the PostgreSQL driver at compile time (`requires org.postgresql.jdbc`, an automatic
  module) for `PGConnection.getNotifications`.
- The poller, the listener and the timer live in the new package `internal.job.claim`, which uses
  `internal.job` but not the other way round; `internal.job` reaches the timer through `DueTimes`.
- Follow-ups (adaptive poll interval, next-due query inside the claim, claiming `limit + 1`,
  jitter, claim reports) are described in the spec.
