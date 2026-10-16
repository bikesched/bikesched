# Specification: scheduling jobs from the admin UI

Status: **implemented**, [ADR 0046](../decisions/0046-scheduling-jobs-from-the-admin-ui.md),
[user docs](../operations/admin.md)

## 1. Goal

An operator schedules a one-off job from the admin page, e.g. to rerun an import by hand, to
send a mail again with a corrected payload, or to trigger an extra run of a recurring job. The
page does what this does on the serving node:

```java
bikesched.newJob("send-invoice")
    .payloadJson("{\"orderId\": \"order-4711\"}")
    .priority(0)
    .maxAttempts(3)
    .idempotencyKey("admin-5e0b")
    .schedule(Duration.ofMinutes(10));
```

Design: board "Schedule jobs" of the
[bikesched admin UI](https://claude.ai/artifact/U2nQTQy9jYSbRfqgnQVFCP) canvas.

## 2. Today

The admin page writes only through retry, cancel and publish event. A new job needs code or SQL.
A typed JSON payload cannot go through `JobSettings.payload(P)` with a configured `JsonMapper`
(a Jackson mapper writes a `String` as a JSON string), the same gap `EventBuilder.payloadJson`
closed for events (ADR 0044).

Scheduling throws `IllegalArgumentException` unless the scheduling node has a handler for the
type (`JobRegistry.requireHandler`). The admin page runs on one node, so it can only schedule
the types that node handles. Which types a node handles is not public today.

## 3. Scope

In scope:

- A "Schedule job" form in the job detail panel, opened from the Jobs tab (type of the picker
  filled in) and from a job's detail ("Schedule similar…": type, payload, priority and max
  attempts copied).
- Type: the types the serving node handles, with their job counts; recurring types marked.
  Picking one copies the payload of its latest job (with `showPayloads(true)` only).
- When: now or in (a number and a unit).
- More options: priority, max attempts, timeout (node default or a choice), idempotency key.
- `JobSettings.payloadJson(String)` in core; a way for the admin to read the handled types.

Not in scope: child jobs (`Job.newJob` belongs in a handler), new recurring jobs or changing a
recurrence (they are registered by code on start), scheduling in a caller's transaction, types
the serving node does not handle (decision 1), editing a scheduled job.

## 4. Design

### Core

- `JobSettings.payloadJson(@Nullable String json)`: sets the payload as JSON text, bypassing the
  `JsonMapper`; `null` means no payload. Every job builder gets it, as `payload` does. Invalid
  JSON fails on schedule (the column is `jsonb`).
- `JobTypeSummary` gains `boolean handled`: a handler is registered on this node.
  `findJobTypes()` also lists handled types without any job yet (all counts 0), so a new type can
  be scheduled for the first time. `DefaultAdmin` gets the registry's type set from
  `DefaultBikesched`.

Rejected: a public `Bikesched.jobTypes()` (the admin needs it next to the counts anyway, and
`Admin` is where the UI reads from).

### API

`POST api/jobs` with a form-encoded body (`type`, `payload`, `delay` as an ISO-8601 duration or
`at` as an ISO-8601 instant, `priority`, `maxAttempts`, `timeout` as an ISO-8601 duration,
`idempotencyKey`; empty fields are left out), the CSRF header and `OPERATE` access, like the
other writes. It answers `{id, created}`: the job's id and whether it is new (`false` when a job
of the type already has the idempotency key, so the page links that job instead). Malformed
input (blank or unhandled type, both `delay` and `at`, a negative delay, `maxAttempts` below 1, a
non-positive timeout, a payload that is not JSON) answers `400`. The server logs who scheduled
which job.

Rejected: a JSON request body (same reason as for events); `Admin.scheduleJob` (`Admin` stays
read-only lookups).

### Page

- Route `#/jobs/new`, with `type` or `from` (a job id, for "Schedule similar") as prefill
  parameters; list filters stay as they are.
- The form replaces the job detail panel; after scheduling, the new job's detail opens live with
  a notice "Scheduled 5e0b7d21, due at 16:44:10." An existing job with the idempotency key opens
  with "Not scheduled again: this job already has the idempotency key invoice-4711."
- Prefill from the latest job of the type (`api/jobs?type=…&limit=1`, then its data), payload
  only with `showPayloads(true)`. A line names the source job and clears it.
- The payload is checked with `JSON.parse` and the numbers as they are typed; scheduling is
  disabled while anything is invalid. The resulting due time is shown in the browser's time.
- A summary line before the button: "Runs as a new send-invoice job at 16:44:10", with the
  type's counts.
- Viewers and read-only admins see no schedule buttons.

## 5. Test plan

- Core: `payloadJson` passes JSON through with a Jackson mapper, `null` schedules no payload,
  invalid JSON fails; `findJobTypes` marks handled types and lists handled types without jobs.
- Admin: `POST api/jobs` schedules a job that runs, honours delay, at, priority, max attempts,
  timeout and idempotency key (`created: false`), rejects viewers, read-only, a missing CSRF
  header, an unhandled type and malformed input with the right status.
- Page: checked in a browser against the admin example (form, prefill, schedule similar, after
  scheduling, phone width, dark mode).

## 6. Implementation steps

1. Core: `payloadJson` on `JobSettings` and its implementations; `JobTypeSummary.handled`;
   `Admin.findJob(type, idempotencyKey)`.
2. Admin: `POST api/jobs`, JSON for `handled`.
3. Page: the form in a new `schedule.js`, like `publish.js`; the button on the Jobs tab and in a
   job's detail.
4. Docs: `docs/admin.md`, `docs/jobs.md` (`payloadJson`), the ADR, this spec's status.

## 7. Decisions

1. Types the serving node does not handle are not listed in the form at all (not even greyed
   out). Allowing them would need a core option to skip the handler check; it waits until
   someone runs the admin in a process without handlers.
2. A recurring type gets a one-off run as a plain job with the payload of its latest run; the
   recurrence is untouched.
3. "When" is up front; priority, max attempts, timeout and idempotency key are under a collapsed
   "More options".
4. Child jobs and new recurrences stay out of scope: they belong in code next to their handler.

## 8. Decided while implementing

- `created` comes from a lookup before scheduling: core gains `Admin.findJob(type,
  idempotencyKey)`, and with an existing job the admin answers its id without calling
  `schedule()`. `IdempotentJobBuilder.schedule()` returns the existing id too, but cannot tell
  the two cases apart. A job stored with the key between the lookup and `schedule()` is reported
  as created; only the notice is wrong then, never the job count.
- The Jobs tab's type picker leaves out handled types without jobs; only the form offers them.
- The delay is a whole number of seconds, minutes, hours or days, sent as an ISO-8601 duration.
- The form has no "At" time picker (Simon, on PR review: not needed for now). The API keeps `at`
  (an ISO-8601 instant) for scripts.
- Timeout offers node default, 30 seconds, 1, 5 or 15 minutes and 1 hour.
- The route parameters are `schedType` and `from`, dropped from the list filters like the publish
  form's.
- A node without any handler shows a note instead of the form.
