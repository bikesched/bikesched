---
title: "44. Publishing events from the admin UI"
weight: 44
---

# 44. Publishing events from the admin UI

Date: 2026-10-14

## Status

Accepted

## Context

A job waiting for an event that never arrives (a lost webhook, a test without the other system)
could only be continued with code or SQL. The admin UI already writes (retry, cancel), so
operators asked to publish events from it too. Two things were missing: a way to publish a payload
that already is JSON text, since `EventBuilder.payload(P)` sends a `String` through the configured
`JsonMapper` (Jackson turns it into a JSON string), and a request body in the framework-neutral
`AdminRequest`.

Alternatives considered:

- A JSON request body: the payload would be JSON inside JSON, and the API still needs the raw text.
- `Admin.publishEvent(...)`: `Admin` stays read-only lookups; publishing is the normal
  `Bikesched.newEvent` API.
- Detecting invalid JSON from the database error (`jsonb` cast): works, but gives a vague message
  and depends on the SQL state; the admin module already parses JSON (ADR 0040).
- A dialog for the form: hides the list and has no URL.

## Decision

- Core gains `EventBuilder.payloadJson(@Nullable String json)`, which passes JSON text on without
  the `JsonMapper`; it replaces an earlier `payload(...)` and the other way round.
- `EventNameSummary` gains `waiting`, and `Admin.findEventNames()` also lists names only waited
  for, so the page can suggest and show them.
- The admin serves `POST api/events` with a form-encoded body (`name`, `key`, `payload`,
  `retention`, `idempotencyKey`), the CSRF header and `OPERATE` access, like retry and cancel.
  `AdminRequest` carries the body as text, capped at 1 MiB by `ExchangeHandler`. The payload is
  checked with nanojson first; the answer is the event id and how many steps received it at once.
- The page shows the form in the event detail panel at `#/events/new`, prefilled from the latest
  event of a picked name, from a waiting job or from an event ("Publish similar"), and previews the
  jobs that would continue.

## Consequences

- Webhook forwarders can use `payloadJson(body)` in code as well.
- Custom `AdminRequest` adapters (servlets) must pass the body for publishing to work; without it
  the API answers `400`.
- Published events do not record who published them; the server log does.
