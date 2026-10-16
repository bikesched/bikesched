---
title: "46. Scheduling jobs from the admin UI"
weight: 46
---

# 46. Scheduling jobs from the admin UI

Date: 2026-10-15

## Status

Accepted

## Context

Operators wanted to schedule jobs from the admin UI as they publish events (ADR 0044): rerun an
import by hand, resend a mail with a corrected payload, add a run of a recurring job. Three things
stood in the way:

- `JobSettings.payload(P)` sends a `String` through the configured `JsonMapper`, so JSON typed
  into a form became a JSON string with Jackson.
- Scheduling requires a handler for the type on the scheduling node, and which types a node
  handles was not visible.
- `IdempotentJobBuilder.schedule()` returns the existing job's id for a known key, so the caller
  cannot tell a new job from an old one.

Alternatives considered:

- Letting the admin schedule types it does not handle: needs a core option to skip the handler
  check, and a typo would create a job no node ever runs. Left for a dedicated admin process
  without handlers, if someone runs one.
- Listing unhandled types greyed out: they cannot be scheduled, so they only add noise.
- A public `Bikesched.jobTypes()`: the page needs the handled flag next to the counts it already
  reads from `Admin`.
- `Admin.scheduleJob(...)`: `Admin` stays read-only lookups; scheduling is the normal
  `Bikesched.newJob` API.
- Child jobs and new recurrences from the UI: both belong in code next to their handler.

## Decision

- Core gains `JobSettings.payloadJson(@Nullable String json)` on every job builder, passing JSON
  text on without the `JsonMapper`, like `EventBuilder.payloadJson`.
- `JobTypeSummary` gains `handled`; `Admin.findJobTypes()` lists the types this node has a handler
  for even without jobs. `Admin.findJob(type, idempotencyKey)` finds the job a key belongs to.
- The admin serves `POST api/jobs` with a form-encoded body (`type`, `payload`, `delay` or `at`,
  `priority`, `maxAttempts`, `timeout`, `idempotencyKey`), the CSRF header and `OPERATE` access.
  With a known idempotency key it answers that job with `created: false` and schedules nothing.
- The page shows the form in the job detail panel at `#/jobs/new`, offering only the handled
  types, prefilled from the latest job of a type or from a job ("Schedule similar…").

## Consequences

- The admin can only schedule the types of the node serving it; an admin on a node without
  handlers cannot schedule at all and says so.
- A recurring type can get extra one-off runs; they are plain jobs, not runs of the recurrence.
- Scheduled jobs do not record who scheduled them; the server log does.
- `payloadJson` is available in code for request bodies and other JSON text.
