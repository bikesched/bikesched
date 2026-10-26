---
title: "62. One history with actions and actors"
weight: 62
---

# 62. One history with actions and actors

Date: 2026-10-26

## Status

Accepted

Supersedes [ADR 0007](0007-job-history-in-separate-table.md) and the job history part of
[ADR 0057](0057-steps-keyed-by-a-time-ordered-id.md). Spec: [history](../specs/history.md).

## Context

- `bikesched_job_history` recorded the state of a job or step after each change, with a prose
  `detail`. It answered "what state was the job in", not "what happened, and who did it": a
  `SCHEDULED` row could be a new job, a failed attempt, a retry, a release or a postponement.
- Published events, nodes registering, stopping and being removed, and what users do in the admin
  UI were not recorded at all.
- A deleted job's or step's history lost its name: the step was identified by its name, the job
  only by its id.
- Alternatives considered: a job history plus a separate event table (two tables, two clean-ups, a
  `union all` for a feed), an audit table written by the admin (a second write path, the same fact
  stored twice), database triggers (rejected in ADR 0007, and they cannot know the user), typed
  columns for every specific instead of JSON (a migration per new specific).

## Decision

- One opt-in table `bikesched_history` for jobs, their steps, events and nodes, keyed by
  (`subject_id`, `id`), written by the stores in the transaction of the change as before
  (`history(retention)` on the builder; a clean-up deletes entries after the retention). No
  foreign keys: entries outlive their subjects.
- Every entry has an `action`, an enum per subject type and for steps (`JobAction`, `StepAction`,
  `EventAction`, `NodeAction`, sealed under `HistoryAction`), next to the resulting `state` of a
  job or step. The step is referenced by `step_id`.
- Every entry has an actor: `actor_type` (`NODE`, `APPLICATION`, `JOB`, `USER`) and, for users
  only, `actor_name`. `node_id` stays the node that wrote the entry, set once the node registered.
  The user goes only on the entry of the action itself; its consequences in the same transaction
  carry the code path's actor and a `caused_by_id` naming the event, job or node that caused
  them, never another entry.
- Names and an action's specifics go into `data`, a JSON object built in SQL by the one recording
  statement from the rows the transaction just wrote, so the names survive deleting the subject.
  Texts are cut to 4000 characters. Core returns `data` as JSON text and parses nothing.
- Entries are ordered by (`created_at` from `clock_timestamp()`, `id`): one clock for all nodes,
  UUIDv7 ids only break ties. No stored sequence and no lock.
- The public API is the package `org.bikesched.history`: a sealed `HistoryEntry` with a record per
  subject (`JobEntry`, `StepEntry`, `EventEntry`, `NodeEntry`). `findJobHistory` returns the job
  and step entries; `JobHistoryEntry` is removed. `StepState` becomes public in
  `org.bikesched.job`.
- The plain write methods of `Bikesched` (`cancel`, `retry`, `removeRecurringJob`) stay and
  record `APPLICATION`. A user is named with `actor(Actor.user(name))` on the job and event
  builders and on the write builders under `bikesched.admin()` (`cancelJob`, `retryJob`,
  `removeRecurringJob`), which the admin UI uses with the signed-in user.
- Idempotency duplicates and no-ops are not recorded: nothing changed.

## Consequences

- One history answers "what happened, and who did it" across jobs, events, nodes and users, and
  feeds a History tab in the admin UI without a `union`.
- A new specific is a code change in one statement, not a migration; `data` is never filtered or
  indexed.
- Rows grow from about 98 to 185 bytes on average (100k job and step rows), indexes from 5.4 to
  8.4 MB.
- Nodes without the history still leave gaps, also for user actions; it should be enabled on all
  nodes.
- Breaking: the table, the `jobHistory(…)` settings and `JobHistoryEntry` are replaced
  (`history(…)`, `HistoryEntry`). Before the first release, the change goes into `V001` in place.
- Recurring jobs are not a subject (no stable id), and a separate retention for user entries is
  deferred.
