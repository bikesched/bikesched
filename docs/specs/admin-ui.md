# Specification: admin UI

Status: **implemented** ([ADR 0038](../decisions/0038-admin-ui.md), user docs:
[Admin UI](../admin.md))

## 1. Goal

Someone running bikesched sees what its jobs are doing, and retries or cancels one, without a
database client. A small optional module serves one page and the JSON it reads, embedded in the
application that runs bikesched:

```java
HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
server.createContext("/bikesched", BikeschedAdmin.of(bikesched).httpHandler());
server.start();   // http://localhost:8081/bikesched/
```

The page lists jobs (newest first, filtered by type and state), shows one job with its steps,
history and last error, lists the nodes with their last heartbeat, and has a retry button on a
failed job and a cancel button on an active one.

## 2. Today

| Question | How it is answered today |
|---|---|
| Which jobs failed? | SQL on `bikesched_jobs` |
| Where is this job stuck? | `findJob(id)` (id, type, state) and `findJobHistory(id)`, if the id is known and the history is enabled; SQL on `bikesched_steps` |
| Are all nodes alive? | SQL on `bikesched_nodes` |
| Retry or cancel a job | `retry(id)`, `cancel(id, message)` from code written for it |

## 3. Scope

In scope:

- Core: read-only lookups the page needs (4.1): jobs by type and state, a job's steps, the job
  types with counts per state, events by name and key, the event names, the nodes.
- A new module `bikesched-admin` (Java only, package `org.bikesched.admin.web`): a framework-neutral
  request handler, an adapter for the JDK's `com.sun.net.httpserver` (no dependency), a JSON
  API, retry and cancel, and the UI as static files (HTML, CSS, JavaScript modules with Preact
  and htm) served from the jar.
- An example in `bikesched-examples/java` (`runAdmin`) serving the page next to a few demo jobs.
- Docs (`docs/admin.md`, `database.md` if an index is added), an ADR.

Not in scope (later slices or separate ideas):

- Spring Boot wiring (a starter property and a servlet registration), servlet and other adapters.
- Login forms, sessions, OAuth/OIDC, rate limiting of failed logins: the built-in option is
  Basic authentication (4.2); anything more is the embedding application's job.
- Recurring job definitions, payload editing, scheduling jobs or publishing events from the page.
- Live updates of the lists (polling, SSE), charts, metrics: lists reload on demand. Only an open
  job detail refreshes itself (4.3).
- A separate frontend toolchain (npm, a bundler) or a standalone admin service.

## 4. Design

### 4.1 Core lookups

A facade keeps `Bikesched` focused: `bikesched.admin()` returns an `Admin` in a new exported
package `org.bikesched.admin`.

```java
// org.bikesched.admin
public interface Admin {
  /** Jobs matching the query, newest first (created_at desc, id desc). */
  List<JobSummary> findJobs(JobQuery query);

  /** The job's steps in the order they started; empty if the job does not exist. */
  List<StepInfo> findSteps(UUID jobId);

  /** All registered nodes, by name. */
  List<NodeInfo> findNodes();

  /** The job's payload and result as raw JSON text; only called with showPayloads(true). */
  Optional<JobData> findJobData(UUID jobId);

  /** Every job type in the table with its number of jobs per state, by name. */
  List<JobTypeSummary> findJobTypes();

  /** Unexpired events matching the query, newest first (published_at desc, id desc). */
  List<EventSummary> findEvents(EventQuery query);

  /** Every event name with its number of unexpired events, by name. */
  List<EventNameSummary> findEventNames();

  /** The steps that published (EVENT_PUBLISH) or received (EVENT_WAIT) the event. */
  List<EventStep> findEventSteps(UUID eventId);

  /** Event wait steps still waiting for the name (optionally a key prefix), oldest first. */
  List<WaitingStep> findWaitingSteps(String eventName, String keyPrefix, int limit);

  /** The event's payload as raw JSON text; only called with showPayloads(true). */
  Optional<String> findEventPayload(UUID eventId);
}

public record JobQuery(String type, Set<JobState> states, Instant createdBefore, UUID beforeId,
    int limit) {
  public static JobQuery newest(int limit) { … }   // plus withType/withStates/before(JobSummary)
}

public record JobSummary(UUID id, String type, JobState state, int attempts, int maxAttempts,
    int priority, Instant scheduledAt, Instant createdAt, Instant updatedAt, Instant finishedAt,
    String lastError, UUID parentJobId, String recurrence, String idempotencyKey) {}

public record StepInfo(String name, String kind, String state, int attempts, String lastError,
    Instant startedAt, Instant finishedAt, UUID childJobId, String childJobType,
    JobState childState) {}   // child fields only for CHILD_JOB steps

public record JobData(String payload, String result) {}   // raw JSON, either may be null

public record JobTypeSummary(String type, Map<JobState, Integer> counts, String recurrence) {}

public record EventQuery(String name, String keyPrefix, Instant publishedBefore, UUID beforeId,
    int limit) {}

public record EventSummary(UUID id, String name, String key, String idempotencyKey,
    Instant publishedAt, Instant expiresAt, int receivedBy) {}

public record EventNameSummary(String name, int count) {}

public record EventStep(UUID jobId, String jobType, JobState jobState, String stepName,
    String kind) {}   // EVENT_PUBLISH or EVENT_WAIT

public record WaitingStep(UUID jobId, String jobType, String stepName, String eventKey,
    Instant since, Instant timeoutAt) {}   // timeoutAt null: waits without a deadline

public record NodeInfo(UUID id, String name, Instant startedAt, Instant lastHeartbeatAt,
    boolean alive) {}   // alive: heartbeat within the node timeout
```

- Pagination is keyset on `(created_at, id)`: the page asks for the next page with the last row's
  values. `limit` is between 1 and 500.
- Payload and result are not part of `JobSummary`: only `findJobData` reads them, so a server
  without `showPayloads(true)` never loads them (4.2) and the list stays cheap.
- `kind` and `state` of a step stay strings: the step kinds and states are internal enums today.
- `findJobTypes` is one `group by type, state` over `bikesched_jobs`; it feeds the type list and
  the counts on the state filter. A type with no jobs left (all deleted after the retention) is
  not listed.
- Stores: one statement each in `JobStore`, `StepStore` and `NodeStore`, read-only transactions.

### 4.2 Module `bikesched-admin`

Depends on core only (`api`); JSON is written by a small internal writer (the API only writes
JSON, requests have no body), so no Jackson.

```java
BikeschedAdmin admin = BikeschedAdmin.of(bikesched);       // retry and cancel enabled
BikeschedAdmin readOnly = BikeschedAdmin.of(bikesched).readOnly(true);
BikeschedAdmin withPayloads = BikeschedAdmin.of(bikesched).showPayloads(true);   // off by default

BikeschedAdmin secured = BikeschedAdmin.of(bikesched).access(AdminAccess.basicAuth()
    .operator("ops", System.getenv("ADMIN_OPS_PASSWORD"))     // may retry and cancel
    .viewer("support", System.getenv("ADMIN_SUPPORT_PASSWORD"))   // read only
    .build());

HttpHandler handler = admin.httpHandler();                  // com.sun.net.httpserver
AdminResponse response = admin.handle(AdminRequest.of("GET", "/api/jobs", query));  // any framework
```

Routes, relative to where the handler is mounted:

| Method | Path | Returns |
|---|---|---|
| GET | `/` | the page (`text/html`), a classpath resource |
| GET | `/api/jobs?type=&state=&before=&limit=` | `{"jobs": [JobSummary…], "next": cursor or null}` |
| GET | `/api/jobs/{id}` | the job's summary, steps and history, plus payload and result as raw JSON with `showPayloads(true)`; 404 if missing |
| GET | `/api/types` | `{"types": [JobTypeSummary…]}` |
| GET | `/api/events?name=&key=&before=&limit=` | `{"events": [EventSummary…], "next": cursor or null}` |
| GET | `/api/events/{id}` | the event's summary and receivers, plus its payload with `showPayloads(true)`; 404 if missing |
| GET | `/api/event-names` | `{"names": [EventNameSummary…]}` |
| GET | `/api/me` | `{"user": …, "level": "VIEW" or "OPERATE", "payloadsShown": …}` |
| GET | `/api/nodes` | `{"nodes": [NodeInfo…]}` |
| POST | `/api/jobs/{id}/retry` | `{"retried": true/false}`; 403 when read-only |
| POST | `/api/jobs/{id}/cancel` | `{"cancelled": true/false}` (message "cancelled from the admin UI"); 403 when read-only |

- Payloads and results are off by default, since they may hold personal or secret data. The
  filter is on the server, not in the page, so the JSON API never hands them out:
  - Without `showPayloads(true)` the handler never asks core for them (the payload and result
    lookup is a separate call, 4.1), so they are not in memory, not in any response and not in
    an error message; `GET /api/jobs/{id}` has no `payload` and `result` fields and carries
    `"payloadsShown": false` instead. No route or parameter returns them.
  - Event payloads follow the same setting through `findEventPayload` and `/api/events/{id}`.
  - The other responses never contain them, whatever the setting: `JobSummary`, `StepInfo`,
    `EventSummary` and the history entries have no payload or result fields (step results are
    not exposed at all).
  - The page keeps the Payload tab and shows "Payload display disabled" there, with the
    `showPayloads(true)` call that turns it on, so a user learns why it is empty.
  - Error messages and step names are shown either way.
- Access: an `AdminAccess` SPI decides per request, `AccessLevel check(AdminRequest)` with
  `NONE`, `VIEW` (GET routes) and `OPERATE` (also retry and cancel). The default
  `AdminAccess.open()` grants `OPERATE` to every request: the embedding application protects
  the path (Spring Security, a reverse proxy). `readOnly(true)` caps every level at `VIEW`.
  - `AdminAccess.basicAuth()` builds the built-in option: HTTP Basic authentication with
    operators and viewers. Missing or wrong credentials answer `401` with `WWW-Authenticate:
    Basic realm="bikesched admin", charset="UTF-8"` (the browser shows its login dialog); a
    viewer's POST answers `403`. Passwords are compared as SHA-256 digests with
    `MessageDigest.isEqual` (constant time, no length leak) and never logged; a failed login is
    logged with the user name and remote address only.
  - Basic authentication sends the password with every request, so it needs HTTPS (the JDK's
    `HttpsServer`, or TLS at a reverse proxy). The handler cannot see TLS behind a proxy, so it
    does not enforce it; the `HttpServer` adapter logs a warning once when it serves Basic
    authentication over a plain `HttpExchange`.
  - `GET /api/me` returns the user, the level and `payloadsShown`, so the page hides retry and
    cancel for viewers and shows "Signed in as ops (operator)" in the header. Basic
    authentication has no logout; closing the browser ends it.
  - Custom implementations can map anything to a level, e.g. a header set by an authenticating
    proxy.
- POSTs require the header `X-Bikesched-Admin: 1`, which the page sets. A cross-site form or
  image tag cannot send it, so a logged-in admin's browser cannot be made to retry or cancel from
  another site. This matters for Basic authentication too: browsers resend cached credentials
  automatically.
- API responses carry `Cache-Control: no-store`. Every response carries a restrictive
  `Content-Security-Policy` (`default-src 'self'`; no inline script, no `eval`, no external
  hosts) and `X-Content-Type-Options: nosniff`.
- `BikeschedException` maps to 503 with the message; malformed ids and parameters to 400.

### 4.3 The page

Static files in the module's resources (`org/bikesched/admin/ui/`), served by the handler under
the mount path with their content type; no external resources:

- `index.html` (a shell loading the app), `admin.css` and `app.js` with a few ES modules per
  view (jobs, events, child job path, type picker), loaded by the browser directly: no build
  step, no npm.
- Styling: one hand-written `admin.css` (about 400 lines), no CSS framework.
  - Design tokens as CSS custom properties on `:root`: surface and text colours, the accent,
    one background, text and dot colour per job and step state, spacing and the font stacks.
    `@media (prefers-color-scheme: dark)` redefines the same tokens, so dark mode is a second
    set of values, not second rules.
  - Small component classes set by the Preact components (`.badge`, `.state-FAILED`,
    `.picker`, `.detail`, `.tabs`, `.steps`, `.timeline`); no inline `style` attributes, so the
    CSP keeps `style-src 'self'`.
  - System fonts (`system-ui` for text, `ui-monospace` for ids, keys and JSON): nothing to
    bundle or license. The design's IBM Plex is only the mockup's choice.
  - Narrow screens: the side bar stacks above the content, tables scroll inside their own box,
    detail panels go below the list.
- Rendering with [Preact](https://preactjs.com) and [htm](https://github.com/developit/htm)
  (about 5 KB together; htm turns tagged template literals into elements, so no JSX compiler
  and no `eval`). Both are vendored, not loaded from a CDN: the build copies their ESM files
  from the WebJars (`org.webjars.npm:preact`, `org.webjars.npm:htm`, versions in
  `gradle/libs.versions.toml`, updated by Renovate) into the module's resources. They are a
  build-time configuration only: the published module still depends on core alone.
- Hash routes make every view linkable, e.g. from an alert: `#/jobs?type=…&state=…`,
  `#/jobs/{id}`, `#/events?name=…&key=…`, `#/events/{id}`.
- Text from the API is rendered as text (Preact escapes it), never as HTML.

Layout and content:

- Header: the nodes as small badges (name, alive or dead, last heartbeat as "12 s ago").
- Design: [bikesched admin UI](https://claude.ai/artifact/U2nQTQy9jYSbRfqgnQVFCP) (clickable
  mockup with sample data).
- Side bar with two sections, Jobs and Events. Each has a select box with a search field (a
  combobox: typing filters the list by substring, so hundreds of types stay usable) and an
  "All" entry on top. Job types show their job count and, below the name, their failed count or
  their recurrence; event names show their number of unexpired events. Picking an entry opens
  that section's list filtered by it.
- Events list: id (short), name, key, published, expires, number of receiving jobs; a key
  filter (prefix match, uses `bikesched_events_name_key_idx` with a name); "Older" pages.
  The event detail shows published, expires, retention, the idempotency key when present,
  "Published by" (the job and step of an `EVENT_PUBLISH` step, or "outside a job"), the jobs
  whose wait step received it (each opens the job) and the payload tab (4.2). The newest
  unexpired event per name and key is tagged "latest": it is the one a new wait receives.
- Waiting jobs (a tab next to the published events of a name): the event wait steps still
  waiting for that name, with key, since and timeout; `findWaitingSteps` uses
  `bikesched_steps_waiting_idx`. Design page "Events".
- State filter: one toggle chip per state with its count for the selected type; several can be
  on, "All states" clears them. A reload button in the header.
- Job table: id (short), type, state as a coloured badge, attempts `n/max`, scheduled, updated,
  last error (truncated); "Older" loads the next page.
- Clicking a row opens the job detail below the table: all fields, payload and result (only
  with `showPayloads(true)`)
  pretty-printed, the steps table, retry and cancel buttons when they apply and the module is not
  read-only, the idempotency key when the job has one.
- Live job detail: while a job's detail is open and the browser tab is visible, the page polls
  `GET /api/jobs/{id}` every 3 seconds and re-renders only that job's row in the table and its
  detail (state, attempts, times, last error, steps, history, child states); the rest of the
  list stays as loaded. The row stays in place even if the job no longer matches the filters,
  until the next reload. Polling pauses while the tab is hidden, stops once the job has ended
  (`SUCCEEDED`, `FAILED`, `CANCELLED`, `COMPENSATION_FAILED`) and starts again after a retry
  from the page. The detail header shows "Live" while polling, "Updated 12:46:03" after it
  stopped, and "Connection lost, retrying" after a failed poll (next try after 10 seconds).
  The response carries an `ETag` (a hash of its body); the page sends `If-None-Match`, so an
  unchanged job answers `304` without a body and nothing re-renders.
- Child jobs (design page "Child jobs"): a `CHILD_JOB` step shows its child's type, short id
  and state (the child's current state while it runs, `childState` once it ended) with "Open
  child". A child job shows its ancestry as a path above its header (root parent first, the
  step that started it, then itself; the middle collapses beyond three levels), a "Parent job"
  button and a note that its outcome completes the parent's step. In the job list a child row
  names its parent under its type. A deleted child shows its type and final state without a
  link; a deleted parent shows its id marked "(deleted)" without a link. The path is built from
  `parentJobId`, one lookup per level.
- A history pane in the job detail: the job's `findJobHistory` entries as a timeline (time,
  step, state, attempt, node, detail), step entries indented under the job's. It appears only
  when the job has entries: the history may be enabled on other nodes than the one serving the
  page, so the entries decide, not the serving node's setting.
- Light and dark theme from `prefers-color-scheme`.

### 4.4 Rejected alternatives

- **A frontend toolchain (React, npm, a bundler)**: a build step and a dependency tree for a
  few views; Preact with htm gives the same component model as plain ES modules.
- **htmx with server-rendered fragments**: the server would build and escape HTML in Java next
  to the JSON API (a second output format, more code, a place for XSS bugs), or need a template
  engine as a dependency in every adapter. Static files plus JSON keep the handler
  framework-neutral and the API usable from scripts.
- **Alpine.js**: its standard build evaluates attribute expressions with `new Function`, which
  the CSP forbids; the CSP build allows no expressions beyond property access, which makes the
  pickers and tabs awkward.
- **Plain JavaScript**: no dependency, but two lists, two details, searchable pickers and the
  child job path written as DOM code grow hard to follow; 5 KB buys components and re-rendering.
- **A login form with sessions**: needs a session store, cookies and CSRF tokens, i.e. a small
  web framework inside the module; Basic authentication covers the "protect a small internal
  page" case, and real login belongs to the embedding application through `AdminAccess`.
- **The JDK's `BasicAuthenticator`**: only works for the `HttpServer` adapter and knows no
  roles; `AdminAccess` works the same in every adapter.
- **Loading Preact from a CDN**: breaks on networks without internet access and needs the CDN in
  the CSP.
- **The lookups as new methods on `Bikesched`**: the main interface is what every user reads;
  operations lookups belong behind `admin()`.
- **A servlet-based module first**: adds `jakarta.servlet-api` and leaves plain applications
  out; the JDK `HttpServer` adapter needs nothing, and the neutral `handle` makes a servlet or
  Spring adapter a few lines each.

### 4.5 Search by id

An id from a log line, an alert or a support ticket should open its job or event without
scrolling the list or editing the URL. The job list and the event list each get a search field
in their head, above the state chips (jobs) or the key filter (events). Design: page "Jobs" of
the [design](https://claude.ai/artifact/U2nQTQy9jYSbRfqgnQVFCP), with a chip for an active
search:

```text
All jobs                                                        Newest first
Job id  [ Full id or its first 8 characters ]
(All states 128) (Scheduled 4) (Running 2) (Failed 7) ...
```

- Input: a full id, or the first 8 or more hex digits of one (the short id the lists show).
  Whitespace, hyphens and case are ignored, so a pasted id matches with or without hyphens.
  Anything else shows a hint next to the field and sends no request.
- A full id or a prefix lists the matching jobs or events, newest first, at most one page, as
  `#/jobs?id=3f2a9c1e` and `#/events?id=3f2a9c1e`. A single match also opens its detail next to
  the list. The type, name, state and key filters are off while an id search is active, since an
  id is more specific than any of them. A chip "id 3f2a9c1e ×" above the table clears it.
- No match: the list says "No job id starts with 3f2a9c1e." or, for a full id, "No job has the id
  … (anymore)." The job search finds only jobs and the event search only events.
- Enter submits; `Escape` clears the field.

Core lookups, separate from `JobQuery` and `EventQuery` because an id prefix excludes their
other filters:

```java
/** Jobs whose id starts with the hex digits (at least 8), newest first. */
List<JobSummary> findJobsByIdPrefix(String prefix, int limit);

/** Unexpired events whose id starts with the hex digits (at least 8), newest first. */
List<EventSummary> findEventsByIdPrefix(String prefix, int limit);
```

- A prefix becomes a range on the primary key: `id between ? and ?`, with the prefix padded
  with `0` and with `f` to 32 hex digits. PostgreSQL compares `uuid` values byte by byte, which
  is the order of their hex digits, so the primary key index serves it and no new index is
  needed.
- This depends on random ids: jobs and events use `UUID.randomUUID()`. With 8 hex digits
  (32 bits), a prefix matches about one job in 4 billion, so a page is almost always one row.
  Time-ordered ids (UUIDv7) would put every job of the same minute behind the same 8 digits;
  a switch to them needs a longer minimum or a search on the random part.
- JSON API: `GET api/jobs?id=` and `GET api/events?id=` take a prefix or a full id and return the
  usual list shape with `next` always `null`; `id` together with another filter answers `400`.

Tests: `AdminTest` finds a job and an event by its first 8 digits, by the full id with and
without hyphens, and nothing for an unknown prefix; a prefix under 8 digits or with other
characters is rejected. `BikeschedAdminTest` covers the `id` parameter, its `400`s and the
combined filter.

## 5. Testing

- Core: `AdminTest` (extends `AbstractBikeschedTest`): `findJobs` by type, by states, keyset
  pages without gaps or duplicates, limit bounds; `findSteps` order and kinds (step, sleep, event
  wait); `findJobTypes` counts per state; `findEvents` by name and key prefix with pages,
  `findEventNames`, `findEventSteps` (publisher and receivers), `findWaitingSteps`;
  `findNodes` alive and dead.
- Module: a JDK `HttpServer` on a random port with `java.net.http.HttpClient`: every route, 404
  and 400, retry and cancel change the job, read-only answers 403, a POST without the header is
  rejected, JSON escaping of error messages with quotes and newlines. Payload filter: a job
  with a marker string in its payload and result; without `showPayloads(true)` no response of
  any route contains the marker or a `payload`/`result` key, with it only `/api/jobs/{id}` does.
- Access: `open()` allows everything; Basic authentication answers `401` with the challenge
  without or with wrong credentials (also a wrong password of the right length), `200` for a
  viewer's GET, `403` for a viewer's POST, `200` for an operator's POST; `readOnly(true)` turns
  an operator into a viewer; `/api/me` reports the level; no log line contains a password.
- ETag: a second `GET /api/jobs/{id}` with the first response's `ETag` answers `304`; after the
  job changed, `200` with a new `ETag`.
- The UI: every static file is served with its content type and the CSP; `index.html` loads
  only files the handler serves (a test reads its module imports). No browser test in the first
  slice.
- The example's test: the page and `/api/jobs` answer.

## 6. Implementation steps

1. Core: `org.bikesched.admin` (records, `Admin`, `Internals` factory), store statements,
   `AdminTest`, `ArchitectureTest` and `module-info.java` updates.
2. Module `bikesched-admin`: Gradle setup (`bikesched.java-library`, publishing), `module-info`
   (`requires jdk.httpserver`), handler, JSON writer, routes, tests.
3. The UI: Gradle task copying Preact and htm from the WebJars, `index.html`, `admin.css`, the
   view modules.
4. Example `org.bikesched.examples.admin` with `runAdmin`, its test, the examples README.
5. Docs `docs/admin.md` (linked from `docs/index.md` and the README feature list and module
   table), `AGENTS.md` layout table, ADR, this spec's status, the IDEAS.md entries removed.

## 7. Open questions

None left; they are decisions 7 to 12 below.

## 8. Decisions

1. Payloads and results are hidden unless the server is built with `showPayloads(true)`, so
   the page does not expose job data by default (4.2).
2. `findEventSteps` gets a partial index `bikesched_steps_event_id_idx on bikesched_steps
   (event_id) where event_id is not null`: only steps that published or received an event
   write to it (`V001` in place, listed in `database.md`).
3. The UI is built with Preact and htm as plain ES modules, vendored from WebJars at build time:
   no build step, no CDN, no `eval`, and no runtime dependency beyond core (4.3, 4.4).
4. An open job detail polls its job every 3 seconds and updates only its own row and detail;
   the lists are not live (4.3).
5. Access goes through an `AdminAccess` SPI (open by default) with built-in Basic
   authentication for operators and viewers (4.2).
6. Styling is one hand-written stylesheet with CSS custom properties for both themes and the
   system font stacks (4.3).
7. The lookups are a facade, `bikesched.admin()`, not methods on `Bikesched` (4.1).
8. The job and event lists get indexes in their sort order: `bikesched_jobs (created_at, id)`,
   `bikesched_jobs (type, created_at, id)` and `bikesched_events (published_at, id)`. Measured
   with 1 million jobs and events, an unfiltered page took about 550 ms (jobs) and 320 ms (events)
   without them, and under 1 ms with them and statements built per filter combination. Their
   columns never change, so state updates don't write to them.
9. Retry and cancel are part of the first slice, switched off with `readOnly(true)`; they reuse
   `retry(id)` and `cancel(id, message)`.
10. The module is `bikesched-admin`, since the JSON API is usable without the page.
11. Search by id is one field per list: the job list finds jobs, the event list finds events
    (4.5). A header search across both, or a link from a job miss to an event with that id, is
    left out.
12. An id prefix matches only the job's or event's own id, not related rows (the step that
    published an event, a child's parent): the detail links those, and the search stays one
    primary key range.

## Decided while implementing

- The module's package is `org.bikesched.admin.web` (JPMS module `org.bikesched.admin.web`):
  `org.bikesched.admin` is the core's exported lookup package, and a package cannot be split
  across modules. The page's files are in `org/bikesched/admin/web/ui/`.
- The lookups live in one read-only `AdminStore` across the tables instead of one statement each
  in `JobStore`, `StepStore` and `NodeStore`; `DefaultAdmin` checks that the node is open and the
  schema migrated, like `findJob`.
- `Admin` also has `findJob(id)` (a `JobSummary`, used by the job detail and the parent path),
  `findEvent(id)` and `findNodes()` uses the node timeout of the serving node.
- `JobSummary` adds `parentJobType` (shown under a child's type in the list) and `nodeName` (the
  node running it); `scheduledAt` is `null` while a job waits without a deadline.
- `StepInfo` adds `waitUntil`, `eventName` and `eventKey`, and names the child fields
  `childJobState` (the child's current state, or the recorded one after the child was deleted) and
  `childJobExists`.
- `EventSummary` adds `latest` (the event a new wait for its name and key would receive);
  `WaitingStep` adds `eventName`.
- The waiting jobs of an event name have their own route, `GET /api/waiting?name=&key=&limit=`.
- `GET /api/jobs/{id}` returns the parent path as `ancestors` (root first, each with the step that
  started the next level, `exists: false` for a deleted parent, at most 20 levels), so the page
  needs one request per poll.
- The list statements are built per filter combination (`type = ?` only with a type, and so on)
  instead of `? is null or …`, which keeps the planner from reading a page along an index; the
  job list joins parents and nodes to the rows of the page only.
- Cursors of `next`/`before` are `time,id` (ISO-8601 instant and UUID).
- `AdminAccess.check` returns an `AccessGrant` (user and level), so `/api/me` can name the user;
  `challenge()` gives the `WWW-Authenticate` value, and an access without one answers `403`.
- `BikeschedAdmin` is immutable: the options return copies.
- Every `GET` gets an `ETag`, not only the job detail; the static files are revalidated with
  `Cache-Control: no-cache`.
- `hooks.module.js` imports the bare specifier `preact`, which a browser resolves only with an
  import map, an inline script the CSP forbids; the `vendorUi` task rewrites the import to the
  relative file.
- After a retry or cancel from the page, the sidebar counts reload but the list keeps its rows, as
  for the live updates.
- Search by id (4.5): the field submits with Enter or a "Search" button (disabled while the hint
  shows). The page sends the id in lower case, so the route and the heading show it that way.
  The range lookup was checked with `explain analyze` on 1 million jobs: an index scan on the
  primary key, under 0.1 ms.
