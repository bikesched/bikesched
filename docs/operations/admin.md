---
title: "Admin UI"
weight: 1
---

# Admin UI

The module `bikesched-admin` (package `org.bikesched.admin.web`) serves a small page showing what
the jobs of a bikesched database are doing: jobs by type and state, a job with its steps, history,
child jobs and last error, events with the jobs that published, received or still wait for them, and
the nodes, online or offline. Failed jobs can be retried and active ones cancelled, and jobs
scheduled and events published, from the page. It is embedded in the application that runs
bikesched; there is no separate service.

```java
Bikesched bikesched = Bikesched.builder().dataSource(dataSource).build();

HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
server.createContext("/bikesched", BikeschedAdmin.of(bikesched).httpHandler());
server.start();   // http://localhost:8081/bikesched/
```

The handler uses the JDK's built-in `com.sun.net.httpserver` (module `jdk.httpserver`). The module
depends on `bikesched-core`, SLF4J and [nanojson](https://github.com/mmastrac/nanojson) for its JSON
responses. The page is static HTML, CSS and JavaScript served from
the jar ([Preact](https://preactjs.com) and [htm](https://github.com/developit/htm) are bundled),
reading a JSON API under `api/`; it loads nothing from other hosts.

The node serving the page does not need to poll: a node built with `autoStartPolling(false)` shows
the jobs of every node on the database. Scheduling jobs from the page needs the job handlers
registered on it, as `newJob(type)` does in code; any node handling the type runs the job. The
runnable [admin example](../../bikesched-examples/README.md#admin) serves the page next to a few
demo jobs.

## Dependencies and shaded jar

Choose one of the two artifacts, not both:

```kotlin
dependencies {
    implementation("org.bikesched:bikesched-admin:0.1.0-SNAPSHOT")
    // Or, with nanojson bundled and relocated to avoid application version conflicts:
    // implementation("org.bikesched:bikesched-admin-shaded:0.1.0-SNAPSHOT")
}
```

The regular jar uses nanojson as a normal runtime dependency. The shaded jar bundles only nanojson,
relocated to `org.bikesched.admin.web.internal.nanojson`; core and SLF4J remain external
dependencies. Its publication does not pull in the original nanojson artifact. Both expose the
same admin API and include the same UI files. Shading does not affect the application's payload
mapper or add a JSON dependency to core.

The regular jar is a JPMS module. The shaded jar is an automatic module with the same name,
`org.bikesched.admin.web`, and must not be used alongside the regular jar. Build both with
`./gradlew :bikesched-admin:build`; `shadedTest` also runs the admin tests against the shaded jar
alongside an unrelocated nanojson dependency.

## Options

`BikeschedAdmin` is immutable: every option returns a copy.

| Option | Default | Effect |
|---|---|---|
| `access(AdminAccess)` | `AdminAccess.open()` | who may see the page and who may retry, cancel, schedule and publish, see [Access](#access) |
| `readOnly(true)` | `false` | no retry, cancel, schedule or publish for anyone: every access level is capped at viewing |
| `showPayloads(true)` | `false` | shows job payloads and results and event payloads, see [Payloads](#payloads) |

## Access

An `AdminAccess` (package `org.bikesched.admin.web.access`, with `AccessGrant` and `AccessLevel`)
decides per request what it may do: `NONE`, `VIEW` (the page and every `GET` route) or `OPERATE`
(also retry, cancel, schedule jobs and publish events). The default, `AdminAccess.open()`, lets
every request operate: protect the path in the application (Spring Security, a reverse proxy) or
pick one of the options below.

HTTP Basic authentication with operators and viewers is built in:

```java
BikeschedAdmin.of(bikesched)
    .access(AdminAccess.basicAuth()
        .operator("ops", System.getenv("ADMIN_OPS_PASSWORD"))         // may change jobs, events
        .viewer("support", System.getenv("ADMIN_SUPPORT_PASSWORD"))   // read only
        .build());
```

- Without valid credentials the admin answers `401` with a `WWW-Authenticate` challenge, so the
  browser shows its login dialog. A viewer's retry, cancel, schedule or publish answers `403`.
- Passwords are kept as SHA-256 digests and compared in constant time. A failed login is logged
  with the user name and the client's address, never with the password.
- Basic authentication sends the password with every request: serve the admin over HTTPS (the
  JDK's `HttpsServer`, or TLS at a reverse proxy). Over a plain `HttpServer` the handler logs a
  warning once.
- There is no logout; closing the browser ends the session.

Any other scheme is a custom `AdminAccess`, e.g. trusting a header an authenticating proxy sets:

```java
AdminAccess byProxy = request -> {
  String user = request.header("X-Forwarded-User");
  return user == null ? AccessGrant.none() : new AccessGrant(user, AccessLevel.VIEW);
};
```

A custom access without a `challenge()` answers denied requests with `403`.

Retry, cancel, schedule and publish are `POST` requests that must carry the header
`X-Bikesched-Admin: 1`, which the page sets. A form or image on another site cannot send it, so a
signed-in browser cannot be made to change jobs or publish events from elsewhere. Every response
carries a strict `Content-Security-Policy` (only the admin's own files, no inline scripts or styles)
and `X-Content-Type-Options: nosniff`.

## Payloads

Payloads and results may hold personal or secret data, so they are hidden by default. The filter
is on the server: without `showPayloads(true)` the admin never reads them from the database, and
no response of the JSON API contains them. The page keeps its Data and Payload tabs and explains
how to turn them on. Error messages, step names, event keys and idempotency keys are always shown.

## The page

- **Header**: the Jobs, Events and Nodes tabs with their number of jobs, unexpired events and
  registered nodes (with "1 offline" while a node has no heartbeat within the node timeout), and a
  reload button. Each tab returns to its list with the filters it was left with; the open tab
  clears them.
- **Type and name**: above the job list a select box with the job types and their number of jobs
  (and failed jobs or the recurrence), above the event list one with the event names and their
  number of unexpired events and waiting jobs (a name jobs wait for but nobody published yet is
  listed too). Both have a search field, so many types stay usable. A repeat icon
  marks recurring job types.
- **Jobs**: newest first (an arrow marks the column a list is sorted by, here Created), filtered
  by type and by one or more states; "Older jobs" loads the next page. A child job names its
  parent below its type; a run of a recurring job has the repeat icon and its recurrence there.
- **Search by id**: the job list and the event list each have an id field. It takes a full id or
  its last 8 characters (the short id the lists show: ids are time-ordered UUIDv7, so their start
  is their creation time and their end is random), with or without hyphens, and finds only
  jobs in the job list and only events in the event list. A single match opens its detail; the
  other filters are off while the search is active.
- **Job detail**: all fields, the idempotency key, the last error, retry and cancel, the steps
  with their duration (a child job step links to the child, a child job shows the path to its
  parents), the job history when recorded, and a Data tab with the payload above the result.
  A running or waiting step's duration counts up; a retried step shows its last attempt's. A
  run of a [recurring job](../jobs/recurring-jobs.md) has a Recurrence tab: the pending run (the
  next one with a countdown, or the current one while it runs or waits), then the last 10 ended runs
  with their state and how long they took from their scheduled time to their end. Jobs of the type
  scheduled without a recurrence are not runs and not listed. The tab refreshes every 3 seconds.
  While the job is open and has not ended, the detail refreshes every 3 seconds and updates the
  job's row in the list; an unchanged job costs a `304` without a body.
- **Step detail**: a step's name in the job detail, in its history and on the event pages opens
  the step on its own (`#/jobs/{jobId}/steps/{stepId}`, keyed by the step's id): its type,
  attempts (checks of a poll), times, deadline, failure handler runs, compensation, the node
  running it, its event or child job, the last error, its result (with `showPayloads(true)`) and
  its entries of the job history. It refreshes every 3 seconds until its job ended.
- **Schedule job** (operators): a form in the detail panel, opened by the button above the job list
  (with the type of the list filled in) or by "Schedule similar…" in a job's detail (type, payload,
  priority and max attempts copied from that job). It offers only the job types the serving node has
  a handler for; picking one copies the payload of its latest job (only with `showPayloads(true)`).
  The job runs now or after a delay; priority, max attempts, timeout and an idempotency key are
  under "More options". The payload must be JSON. For a recurring type it adds one run and leaves
  the recurrence as it is. Once scheduled, the new job's detail opens; with an idempotency key a job
  of the type already has, that job opens instead and nothing is scheduled. The server logs every
  scheduled job with the user.
- **Events**: newest first, by name and key prefix. The detail shows who published the event (a
  job's step or outside a job), the jobs that received it and its payload; "latest" marks the event
  a new wait for its name and key would receive. The "Waiting jobs" tab lists the event waits still
  waiting for the name.
- **Publish event** (operators): a form in the detail panel, opened by the button above the event
  list, by "Publish…" on a waiting job or "Publish event…" on a waiting step in a job's detail (name
  and key filled in) or by "Publish similar" in an event's detail (copied from that event). Picking
  a known name copies key and payload from its latest unexpired event (the payload only with
  `showPayloads(true)`); the keys jobs wait for are offered below the key. Before publishing, the
  form lists the jobs waiting for that name and key, which continue right away; the payload must be
  JSON. Retention (node default, 0, 1 hour, 1 day, 7 days) and an idempotency key are optional. The
  server logs every published event with the user.
- **Nodes**: every registered node by name, online or offline, with its start, last heartbeat and
  the number of jobs it runs. The list and the open node refresh every 3 seconds while the tab is
  visible. The node detail shows how long ago its last heartbeat was, its uptime and the jobs it
  runs, each linked; an offline node says that other nodes release its jobs and later remove it. A
  running job's Node field links to the node.

Every view is a link (`#/jobs?type=send-invoice&state=FAILED`, `#/jobs?id=3f2a9c1e`, `#/jobs/{id}`,
`#/jobs/{id}/steps/{stepId}`, `#/events/{id}`, `#/events/new?pubName=payment-received&pubKey=order-42`, `#/nodes/{id}`), so
it can be shared or put into an alert. The page follows the browser's light or dark mode.

## Other HTTP servers

`handle(AdminRequest)` works with any framework: translate the request (method, path relative to the
mount point, raw query string, headers, client address, body as text) and write the `AdminResponse`
back. A servlet, for example:

```java
BikeschedAdmin admin = BikeschedAdmin.of(bikesched);

protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
  Map<String, String> headers = new HashMap<>();
  Collections.list(req.getHeaderNames()).forEach(name -> headers.put(name, req.getHeader(name)));
  String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
  AdminResponse response = admin.handle(new AdminRequest(
      req.getMethod(), path, req.getQueryString(), headers, req.getRemoteAddr(),
      new String(req.getInputStream().readNBytes(1024 * 1024), StandardCharsets.UTF_8)));
  resp.setStatus(response.status());
  response.headers().forEach(resp::setHeader);
  resp.getOutputStream().write(response.body());
}
```

Mount it so that the page's URL ends with a slash (`/bikesched/`): its files are loaded relative to
it.

## JSON API

The page's API can be used from scripts too. Paths are relative to the mount point; every response
is JSON with `Cache-Control: no-store` and an `ETag`.

| Method | Path | Returns |
|---|---|---|
| GET | `api/jobs?type=&state=&node=&before=&limit=` | `{"jobs": [...], "next": cursor}`: newest first; `state` takes several states separated by commas, `node` a node id (the jobs it runs now), `before` the previous page's `next`, `limit` 1 to 500 (default 50) |
| GET | `api/jobs?id=&limit=` | `{"jobs": [...], "next": null}`: jobs whose id ends with `id` (at least 8 hex digits, or a full id), newest first; not combined with the other filters |
| GET | `api/jobs/{id}` | `{"job", "ancestors", "steps", "history", "payloadsShown"}`, plus `payload` and `result` with `showPayloads(true)` |
| GET | `api/steps/{id}` | `{"step", "job", "history", "payloadsShown"}`: one step by its id with its job's summary and the step's history entries, plus `result` with `showPayloads(true)` |
| GET | `api/types` | `{"types": [{"type", "total", "counts", "recurrence", "handled"}]}`: `handled` if the serving node has a handler for the type (handled types without jobs are listed too) |
| GET | `api/events?name=&key=&before=&limit=` | `{"events": [...], "next": cursor}`: unexpired events, newest first; `key` is a prefix |
| GET | `api/events?id=&limit=` | `{"events": [...], "next": null}`: unexpired events whose id ends with `id`, as for jobs |
| GET | `api/events/{id}` | `{"event", "steps", "payloadsShown"}`, plus `payload` with `showPayloads(true)` |
| GET | `api/event-names` | `{"names": [{"name", "count", "waiting"}]}`: unexpired events and waiting steps per name |
| GET | `api/recurring-runs?type=&limit=` | `{"type", "pending", "previous": [...]}`: the pending run of a recurring job (`null` if none) and its latest ended runs, newest first |
| GET | `api/waiting?name=&key=&limit=` | `{"steps": [...]}`: event waits still waiting for the name, longest waiting first |
| GET | `api/nodes` | `{"nodes": [{"id", "name", "startedAt", "lastHeartbeatAt", "alive", "runningJobs"}]}`: by name |
| GET | `api/nodes/{id}` | `{"node": {...}}`, the same fields; `404` once the node is removed |
| GET | `api/me` | `{"user", "level", "readOnly", "payloadsShown"}` |
| POST | `api/jobs/{id}/retry` | `{"retried": true or false}` (only failed jobs are retried) |
| POST | `api/jobs/{id}/cancel` | `{"cancelled": true or false}` (message "cancelled from the admin UI") |
| POST | `api/jobs` | `{"id", "created"}`: schedules a job from a form-encoded body (`type`, `payload` as JSON text, `delay` as ISO-8601 or `at` as an ISO-8601 instant, `priority`, `maxAttempts`, `timeout` as ISO-8601, `idempotencyKey`); `created` is `false` when a job of the type has the idempotency key, whose id is returned |
| POST | `api/events` | `{"id", "received"}`: publishes an event from a form-encoded body (`name`, `key`, `payload` as JSON text, `retention` as ISO-8601, `idempotencyKey`); `received` counts the steps that got it at once |

Errors are `{"error": "..."}` with `400` (malformed parameter, id, job or event, a type without a
handler on the serving node), `401`/`403` (access),
`404` (unknown job, step, event or path), `405` (wrong method) or `503` (the database cannot be reached).

## Lookups in code

The page reads through `bikesched.admin()`, read-only lookups in `bikesched-core` (package
`org.bikesched.admin`) that also suit custom dashboards and support tools:

```java
Admin admin = bikesched.admin();
List<JobSummary> failed = admin.findJobs(JobQuery.newest(50).withStates(JobState.FAILED));
List<JobSummary> older = admin.findJobs(JobQuery.newest(50).withStates(JobState.FAILED)
    .after(failed.getLast()));
List<StepInfo> steps = admin.findSteps(failed.getFirst().id());
```

| Method | Returns |
|---|---|
| `findJobs(JobQuery)` | jobs by type, states and the node running them, newest first, in pages (`after(lastJob)`) |
| `findJobsByIdSuffix(suffix, limit)` | jobs whose id ends with at least 8 hex digits (or a full id), newest first |
| `findJob(id)` | one job's summary: state, attempts, times, last error, parent, recurrence, idempotency key, node |
| `findJob(type, idempotencyKey)` | the job of a type with an idempotency key, the one scheduling with that key returns |
| `findJobData(id)` | the payload and result as JSON text |
| `findSteps(id)` | the job's steps in the order they started, with event and child job details |
| `findStep(stepId)`, `findStepResult(stepId)` | one step by its id, its result as JSON text |
| `findJobTypes()` | every job type with its number of jobs per state and whether this node handles it (handled types without jobs too) |
| `findRecurringJobRuns(type, limit)` | a recurring job's pending run and its latest ended runs, newest first |
| `findEvents(EventQuery)` | unexpired events by name and key prefix, newest first, in pages |
| `findEventsByIdSuffix(suffix, limit)` | unexpired events whose id ends with at least 8 hex digits |
| `findEvent(id)`, `findEventPayload(id)` | one event, its payload as JSON text |
| `findEventNames()` | every event name with its number of unexpired events |
| `findEventSteps(id)` | the steps that published or received the event |
| `findWaitingSteps(name, keyPrefix, limit)` | event waits still waiting for the name |
| `findNodes()` | the registered nodes, alive or not, with the number of jobs each runs |
| `findNode(id)` | one registered node |

Summaries never contain payloads or results; only `findJobData` and `findEventPayload` read them.
