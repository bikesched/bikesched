# Specification: publishing events from the admin UI

Status: **implemented**, [ADR 0044](../decisions/0044-publishing-events-from-the-admin-ui.md),
[user docs](../admin.md)

## 1. Goal

An operator publishes an event from the admin page, e.g. to continue a job stuck waiting for a
webhook that never arrived, or to replay an event while testing. The page does what this does on
the serving node:

```java
bikesched.newEvent("payment-received", "order-4711")
    .payloadJson("{\"amount\": 42.50}")
    .retention(Duration.ofDays(1))
    .idempotencyKey("admin-7c1e")
    .publish();
```

Design: board "Publish events" of the
[bikesched admin UI](https://claude.ai/artifact/U2nQTQy9jYSbRfqgnQVFCP) canvas.

## 2. Today

The admin page only reads events; retry and cancel are its only writes. Publishing needs code or
SQL. A typed JSON payload cannot go through `EventBuilder.payload(P)` with a configured
`JsonMapper`: a Jackson mapper writes a `String` as a JSON string, not as the JSON it contains.

## 3. Scope

In scope:

- A "Publish event" form in the event detail panel, opened from the Events tab, from a row of
  the Waiting jobs tab or a waiting step in a job's detail (name and key filled in) and from an
  event's detail ("Publish similar").
- Name: a text input with suggestions (the known names); picking one copies key and payload from
  its latest unexpired event. Key, payload (JSON), retention, idempotency key.
- Before publishing: the jobs waiting for that name and key; the keys jobs wait for, one click
  each.
- `EventBuilder.payloadJson(String)` in core; `EventNameSummary` gains the waiting count, and
  `findEventNames()` also lists names that only have waiting steps.

Not in scope: publishing jobs (a "new job" form), editing or deleting events, publishing to a
caller's connection.

## 4. Design

### Core

- `EventBuilder.payloadJson(@Nullable String json)`: sets the payload as JSON text, bypassing the
  `JsonMapper`; `null` means no payload. Redeclared with covariant returns on `NodeEventBuilder`
  and `StepEventBuilder`. Invalid JSON fails on publish (the column is `jsonb`).
- `EventNameSummary(name, count, waiting)`: `findEventNames()` unions the unexpired events by name
  with the waiting `EVENT_WAIT` steps by name, so a name nobody published yet is listed with
  `count` 0. The Events tab's picker shows the waiting count too, so the Waiting jobs tab of such
  a name becomes reachable.

### API

`POST api/events` with a form-encoded body (`name`, `key`, `payload`, `retention` as an ISO-8601
duration, `idempotencyKey`; empty fields are left out), the CSRF header and `OPERATE` access, like
retry and cancel. It answers `{id, received}`: the new event's id and how many steps received it
at once. Malformed input (blank name or key, a negative or unparsable retention, a payload that
is not JSON) answers `400`. The server logs who published which event. `AdminRequest` gains a
`body` (text; `null` for none), filled by `ExchangeHandler`, capped at 1 MiB (`413` above).

Rejected: a JSON request body (form encoding carries the payload text unchanged, without JSON
inside JSON); `Admin.publishEvent` (the `Admin` interface stays read-only lookups).

### Page

- Route `#/events/new`, with `pubName`/`pubKey` (from a waiting job) or `from` (an event id, for
  "Publish similar") as prefill parameters; list filters stay as they are.
- The form replaces the event detail panel; after publishing the new event's detail opens with
  a notice "Published 7c1e09a2. 2 jobs received it."
- Prefill from the latest event (`api/events?name=…&limit=1`, then its detail): the key always,
  the payload only with `showPayloads(true)`. A line names the source event and clears it.
- The payload is checked with `JSON.parse` as it is typed; publishing is disabled while it is
  invalid. An unknown name shows a spelling warning.
- Waiting preview and waiting keys from one `api/waiting?name=…` lookup, filtered to the exact
  key on the page.
- Retention: node default, 0, 1 hour, 1 day, 7 days.
- Viewers and read-only admins see no publish buttons.

## 5. Test plan

- Core: `payloadJson` passes JSON through with a Jackson mapper, `null` publishes no payload,
  invalid JSON fails; `findEventNames` lists waited-only names with their waiting count.
- Admin: `POST api/events` publishes and wakes a waiting job, honours retention and idempotency
  key, rejects viewers, read-only, a missing CSRF header and malformed input with the right
  status.
- Page: checked in a browser against the admin example (form, prefill, preview, after publish,
  phone width, dark mode).

## 6. Decisions

- Name suggestions are the known names, free text allowed (board, open question 2).
- The idempotency key is in the form, optional (open question 1).
- Retention offers fixed choices (open question 3); a custom duration was left out until asked.
- The form lives in the detail panel, not a dialog: the list stays visible and the form has a
  URL.

## 7. Decided while implementing

- The server checks the payload with the admin module's JSON parser (nanojson, ADR 0040) before
  publishing, so invalid JSON answers `400` with the parser's message instead of a database error.
- `received` is read after publishing from the event's summary (`receivedBy`), not returned by
  `publish()`.
- Route parameters are `pubName`, `pubKey` and `from`; they are dropped from the list filters, so
  closing the form keeps the list as it was.
- A viewer opening `#/events/new` directly sees "Only operators can publish events." instead of
  the form.
- Without `showPayloads(true)` the prefill copies only the key, and the payload hint says why.
- A "Publish…" column on the Waiting jobs tab opens the form with that step's name and key; the
  key is kept while the payload comes from the latest event of the name.
- A waiting `EVENT_WAIT` step in a job's detail has "Publish event…" too, opening the same form
  next to the published events of its name.
