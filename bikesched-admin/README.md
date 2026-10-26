# bikesched-admin

A small admin page and JSON API for a bikesched database, embedded in the application that runs
bikesched: no separate service, no build step, no dependencies beyond `bikesched-core`. User
documentation: [Admin UI](../docs/operations/admin.md). Design: [spec](../docs/specs/admin-ui.md),
[publishing events](../docs/specs/admin-publish-events.md), [nodes](../docs/specs/admin-nodes.md),
[scheduling jobs](../docs/specs/admin-schedule-jobs.md).

## Scope

- Shows jobs by type and state, a job with its steps, history, child jobs, last error and
  (optionally) payload and result, events with who published, received or waits for them, the
  nodes with their heartbeat, and the history of all of them with who acted
  ([spec](../docs/specs/history.md)).
- Four actions: retry a failed job, cancel an active one, schedule a job and publish an event.
  Nothing else writes.
- Reads only through `bikesched.admin()` (package `org.bikesched.admin` in core), read-only lookups
  that never return payloads in lists. The serving node does not need to poll.

## How it works

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
server.createContext("/bikesched", BikeschedAdmin.of(bikesched).httpHandler());
```

- `BikeschedAdmin` is immutable (`access`, `readOnly`, `showPayloads` return copies) and has two
  entry points: `handle(AdminRequest)` → `AdminResponse`, framework-neutral, for servlets, Spring
  or anything else, and `httpHandler()`, an adapter for the JDK's `com.sun.net.httpserver`.
- Every request first goes through the configured `AdminAccess`, then either to the static files
  of the page or to the JSON API under `api/`.
- The page is plain static files in the jar: ES modules using Preact and htm, copied from their
  WebJars into `ui/vendor/` by the Gradle task `vendorUi`; the logo is `docs/logo.svg`, copied by
  `logoUi`. It loads nothing from other hosts, routes with the URL hash (every view is a shareable
  link) and follows light or dark mode.
- Lists load on demand; an open job that has not ended refreshes every 3 seconds. Responses carry
  an `ETag`, so an unchanged job costs a `304` without a body.

## Access and authentication

Everything about access is in the package `org.bikesched.admin.web.access`. `AdminAccess` decides
per request: `NONE`, `VIEW` (the page and every `GET`) or `OPERATE` (also retry, cancel and
publish).

- `AdminAccess.open()` is the default and lets everyone operate. Protect the path in the
  application (Spring Security, a reverse proxy) or configure one of the options below.
- `AdminAccess.basicAuth()` is built in: operators and viewers with passwords, kept as SHA-256
  digests and compared in constant time. Missing credentials get `401` with a challenge, a viewer's
  retry, cancel or publish gets `403`. Serve it over HTTPS; on a plain `HttpServer` a warning is
  logged once.
- Any other scheme is a custom `AdminAccess`, e.g. trusting a header set by an authenticating
  proxy.
- `readOnly(true)` caps every level at `VIEW`.

Independent of access: retry, cancel and publish are `POST`s that need the header
`X-Bikesched-Admin: 1` (blocks cross-site requests from a signed-in browser), and every response
sets a strict Content-Security-Policy (own files only, no inline scripts or styles) and `nosniff`.

## Payloads

Payloads and results may hold personal or secret data, so they are hidden by default. The filter
is on the server: without `showPayloads(true)` they are never read from the database and no API
response contains them.

## Structure

| Path (`src/main/…`) | Contents |
|---|---|
| `java/…/BikeschedAdmin` | entry point and options |
| `java/…/AdminHandler` | access check, routing, JSON responses, retry, cancel and publish, ETags and security headers |
| `java/…/StaticFiles` | loads the files of `ui/` from the jar with their content types |
| `java/…/AdminRequest`, `AdminResponse` | the framework-neutral request and response |
| `java/…/ExchangeHandler` | the adapter to `com.sun.net.httpserver` |
| `java/…/access/AdminAccess`, `AccessGrant`, `AccessLevel` | the access SPI and its result (package `org.bikesched.admin.web.access`) |
| `java/…/access/OpenAccess`, `BasicAuthAccess`, `BasicAuthBuilder` | the two built-in accesses |
| `java/…/Json` | a minimal JSON writer, so no JSON library is needed |
| `resources/…/ui/index.html`, `app.js` | the shell, header with the Jobs and Events tabs, and hash routing |
| `resources/…/ui/jobs.js`, `events.js` | the job and event lists and details |
| `resources/…/ui/schedule.js` | the form that schedules a job |
| `resources/…/ui/publish.js` | the form that publishes an event |
| `resources/…/ui/nodes.js` | the node list and the live node detail |
| `resources/…/ui/api.js`, `format.js`, `picker.js`, `html.js` | fetch helpers, formatting, the searchable select, Preact and htm setup |
| `resources/…/ui/admin.css` | all styles, light and dark |

## Limitations

- No logout with Basic authentication; closing the browser ends the session.
- Lists are not live: they reload on navigation or with the reload button. Only an open job, the
  node list and the History tab refresh by themselves; the History tab misses an entry of a
  transaction open for more than a minute until the next reload.
- Events are listed only until they expire.
- Search by id needs at least the first 8 hex digits and relies on bikesched's random UUIDs.
- A job's History tab appears only when the history is enabled; the History tab in the header
  says when the serving instance does not record it.
- A retried step shows the duration of its last attempt.
- One database per `BikeschedAdmin`; no aggregation across several bikesched instances.

The runnable [admin example](../bikesched-examples/README.md#admin) serves the page next to a few
demo jobs.
