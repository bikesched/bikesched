# 38. Admin UI as static files on a framework-neutral handler

Date: 2026-10-11

## Status

Accepted. JSON writer and dependency choices superseded by
[ADR 0040](0040-admin-nanojson-and-shaded-artifact.md).

## Context

Seeing what jobs do (which failed, where one is stuck, whether all nodes are alive) needed SQL or
code written for it. An admin page needs lookups the core did not offer (jobs by type and state,
steps, counts per state, events and their receivers), an HTTP server, a UI and access control,
while the core keeps its two runtime dependencies and applications use different web stacks (none,
Spring, Ktor). Payloads may hold personal or secret data. The spec is
[Admin UI](../specs/admin-ui.md).

Alternatives considered: the lookups as methods on `Bikesched`; server-rendered HTML with htmx (a
second output format with escaping in Java, or a template engine); a frontend toolchain (npm, a
bundler) or Alpine.js (needs `eval`, which a strict CSP forbids); Preact from a CDN; a servlet
module first (adds `jakarta.servlet-api`); a login form with sessions; the JDK's
`BasicAuthenticator` (no roles, `HttpServer` only); hiding payloads in the page only.

## Decision

- The core gets read-only lookups behind `bikesched.admin()` (package `org.bikesched.admin`, one
  read-only transaction each, keyset pagination on `(created_at, id)` and `(published_at, id)`),
  with indexes in the lists' sort order (`bikesched_jobs (created_at, id)`, also per type, and
  `bikesched_events (published_at, id)`) and a partial index `bikesched_steps_event_id_idx` for
  the steps of an event. Summaries carry no payloads or results; separate lookups read them.
- A module `bikesched-admin` (package `org.bikesched.admin.web`, depends on core only) serves the
  page and a JSON API through `BikeschedAdmin.handle(AdminRequest)`, with an adapter for the JDK's
  `com.sun.net.httpserver`. JSON is written by a small internal writer.
- The page is static HTML, one stylesheet and ES modules using Preact and htm, copied from their
  WebJars into the jar at build time: no build step, no CDN, no inline scripts or styles, so every
  response can carry `Content-Security-Policy: default-src 'self'`.
- An `AdminAccess` SPI decides per request (`NONE`, `VIEW`, `OPERATE`); `AdminAccess.open()` is the
  default, `AdminAccess.basicAuth()` the built-in option with operators and viewers;
  `readOnly(true)` caps every request at viewing. Retry and cancel need the header
  `X-Bikesched-Admin: 1` against cross-site requests.
- Payloads and results are only read with `showPayloads(true)`; without it they never leave the
  database.
- An open job detail polls its job every 3 seconds with an `ETag`; the lists reload on demand.

## Consequences

- Applications get an admin page with one line and no new runtime dependency; the lookups also
  serve custom dashboards.
- Other frameworks need a few lines of adapter code around `handle`; a Spring or servlet adapter
  can be added later without changing the handler.
- Basic authentication is only safe over HTTPS, which the handler cannot enforce behind a proxy.
- Preact and htm updates come through the version catalog (Renovate); the vendored files are not
  checked in.
- Three more indexes on jobs and events cost space and inserts, but not state updates: their
  columns never change. A rare state within one type still filters along the type's index.
