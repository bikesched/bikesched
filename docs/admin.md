---
title: "Admin UI"
weight: 12
---

# Admin UI

The module `bikesched-admin` (package `org.bikesched.admin.web`) serves a small page showing what
the jobs of a bikesched database are doing: jobs by type and state, a job with its steps, history,
child jobs and last error, events with the jobs that published, received or still wait for them,
and the nodes with their last heartbeat. Failed jobs can be retried and active ones cancelled from
the page. It is embedded in the application that runs bikesched; there is no separate service.

```java
Bikesched bikesched = Bikesched.builder().dataSource(dataSource).build();

HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
server.createContext("/bikesched", BikeschedAdmin.of(bikesched).httpHandler());
server.start();   // http://localhost:8081/bikesched/
```

The handler uses the JDK's built-in `com.sun.net.httpserver` (module `jdk.httpserver`), so the
module depends on `bikesched-core` only. The page is static HTML, CSS and JavaScript served from
the jar ([Preact](https://preactjs.com) and [htm](https://github.com/developit/htm) are bundled),
reading a JSON API under `api/`; it loads nothing from other hosts.

The node serving the page does not need to poll: a node built with `autoStartPolling(false)` shows
the jobs of every node on the database. The runnable [admin example](../bikesched-examples/README.md#admin)
serves the page next to a few demo jobs.

## Options

`BikeschedAdmin` is immutable: every option returns a copy.

| Option | Default | Effect |
|---|---|---|
| `access(AdminAccess)` | `AdminAccess.open()` | who may see the page and who may retry and cancel, see [Access](#access) |
| `readOnly(true)` | `false` | no retry and cancel for anyone: every access level is capped at viewing |
| `showPayloads(true)` | `false` | shows job payloads and results and event payloads, see [Payloads](#payloads) |

## Access

An `AdminAccess` decides per request what it may do: `NONE`, `VIEW` (the page and every `GET`
route) or `OPERATE` (also retry and cancel). The default, `AdminAccess.open()`, lets every request
operate: protect the path in the application (Spring Security, a reverse proxy) or pick one of the
options below.

HTTP Basic authentication with operators and viewers is built in:

```java
BikeschedAdmin.of(bikesched)
    .access(AdminAccess.basicAuth()
        .operator("ops", System.getenv("ADMIN_OPS_PASSWORD"))         // may retry and cancel
        .viewer("support", System.getenv("ADMIN_SUPPORT_PASSWORD"))   // read only
        .build());
```

- Without valid credentials the admin answers `401` with a `WWW-Authenticate` challenge, so the
  browser shows its login dialog. A viewer's retry or cancel answers `403`.
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

Retry and cancel are `POST` requests that must carry the header `X-Bikesched-Admin: 1`, which the
page sets. A form or image on another site cannot send it, so a signed-in browser cannot be made to
change jobs from elsewhere. Every response carries a strict `Content-Security-Policy` (only the
admin's own files, no inline scripts or styles) and `X-Content-Type-Options: nosniff`.

## Payloads

Payloads and results may hold personal or secret data, so they are hidden by default. The filter
is on the server: without `showPayloads(true)` the admin never reads them from the database, and
no response of the JSON API contains them. The page keeps its Payload tab and explains how to turn
them on. Error messages, step names, event keys and idempotency keys are always shown.

## The page

- **Header**: the registered nodes, alive (heartbeat within the node timeout) or dead, and a
  reload button.
- **Side bar**: the job types with their number of jobs (and failed jobs or the recurrence) and
  the event names with their number of unexpired events. Both are select boxes with a search
  field, so many types stay usable.
- **Jobs**: newest first, filtered by type and by one or more states; "Older jobs" loads the next
  page. A child job names its parent below its type.
- **Search by id**: the job list and the event list each have an id field. It takes a full id or
  its first 8 characters (the short id the lists show), with or without hyphens, and finds only
  jobs in the job list and only events in the event list. A single match opens its detail; the
  other filters are off while the search is active.
- **Job detail**: all fields, the idempotency key, the last error, retry and cancel, the steps (a
  child job step links to the child, a child job shows the path to its parents), the job history
  when recorded, and the payload and result. While the job is open and has not ended, the detail
  refreshes every 3 seconds and updates the job's row in the list; an unchanged job costs a `304`
  without a body.
- **Events**: newest first, by name and key prefix. The detail shows who published the event (a
  job's step or outside a job), the jobs that received it and its payload; "latest" marks the event
  a new wait for its name and key would receive. The "Waiting jobs" tab lists the event waits still
  waiting for the name.

Every view is a link (`#/jobs?type=send-invoice&state=FAILED`, `#/jobs?id=3f2a9c1e`, `#/jobs/{id}`,
`#/events/{id}`), so
it can be shared or put into an alert. The page follows the browser's light or dark mode.

## Other HTTP servers

`handle(AdminRequest)` works with any framework: translate the request (method, path relative to
the mount point, raw query string, headers, client address) and write the `AdminResponse` back. A
servlet, for example:

```java
BikeschedAdmin admin = BikeschedAdmin.of(bikesched);

protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
  Map<String, String> headers = new HashMap<>();
  Collections.list(req.getHeaderNames()).forEach(name -> headers.put(name, req.getHeader(name)));
  String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
  AdminResponse response = admin.handle(new AdminRequest(
      req.getMethod(), path, req.getQueryString(), headers, req.getRemoteAddr()));
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
| GET | `api/jobs?type=&state=&before=&limit=` | `{"jobs": [...], "next": cursor}`: newest first; `state` takes several states separated by commas, `before` the previous page's `next`, `limit` 1 to 500 (default 50) |
| GET | `api/jobs?id=&limit=` | `{"jobs": [...], "next": null}`: jobs whose id starts with `id` (at least 8 hex digits, or a full id), newest first; not combined with the other filters |
| GET | `api/jobs/{id}` | `{"job", "ancestors", "steps", "history", "payloadsShown"}`, plus `payload` and `result` with `showPayloads(true)` |
| GET | `api/types` | `{"types": [{"type", "total", "counts", "recurrence"}]}` |
| GET | `api/events?name=&key=&before=&limit=` | `{"events": [...], "next": cursor}`: unexpired events, newest first; `key` is a prefix |
| GET | `api/events?id=&limit=` | `{"events": [...], "next": null}`: unexpired events whose id starts with `id`, as for jobs |
| GET | `api/events/{id}` | `{"event", "steps", "payloadsShown"}`, plus `payload` with `showPayloads(true)` |
| GET | `api/event-names` | `{"names": [{"name", "count"}]}` |
| GET | `api/waiting?name=&key=&limit=` | `{"steps": [...]}`: event waits still waiting for the name, longest waiting first |
| GET | `api/nodes` | `{"nodes": [{"id", "name", "startedAt", "lastHeartbeatAt", "alive"}]}` |
| GET | `api/me` | `{"user", "level", "readOnly", "payloadsShown"}` |
| POST | `api/jobs/{id}/retry` | `{"retried": true or false}` (only failed jobs are retried) |
| POST | `api/jobs/{id}/cancel` | `{"cancelled": true or false}` (message "cancelled from the admin UI") |

Errors are `{"error": "..."}` with `400` (malformed parameter or id), `401`/`403` (access), `404`
(unknown job, event or path), `405` (wrong method) or `503` (the database cannot be reached).

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
| `findJobs(JobQuery)` | jobs by type and states, newest first, in pages (`after(lastJob)`) |
| `findJobsByIdPrefix(prefix, limit)` | jobs whose id starts with at least 8 hex digits (or a full id), newest first |
| `findJob(id)` | one job's summary: state, attempts, times, last error, parent, recurrence, idempotency key, node |
| `findJobData(id)` | the payload and result as JSON text |
| `findSteps(id)` | the job's steps in the order they started, with event and child job details |
| `findJobTypes()` | every job type with its number of jobs per state |
| `findEvents(EventQuery)` | unexpired events by name and key prefix, newest first, in pages |
| `findEventsByIdPrefix(prefix, limit)` | unexpired events whose id starts with at least 8 hex digits |
| `findEvent(id)`, `findEventPayload(id)` | one event, its payload as JSON text |
| `findEventNames()` | every event name with its number of unexpired events |
| `findEventSteps(id)` | the steps that published or received the event |
| `findWaitingSteps(name, keyPrefix, limit)` | event waits still waiting for the name |
| `findNodes()` | the registered nodes, alive or not |

Summaries never contain payloads or results; only `findJobData` and `findEventPayload` read them.
