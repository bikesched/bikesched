# Specification: nodes in the admin UI

Status: **implemented**, [ADR 0045](../decisions/0045-nodes-in-the-admin-ui.md),
[user docs](../admin.md)

## 1. Goal

An operator sees which nodes are registered, whether they are online and what each one runs right
now, without reloading the page:

```java
Admin admin = bikesched.admin();
NodeInfo node = admin.findNode(nodeId).orElseThrow();       // node.runningJobs()
List<JobSummary> running = admin.findJobs(JobQuery.newest(100).withNode(nodeId));
```

Design: board "Nodes" of the
[bikesched admin UI](https://claude.ai/artifact/U2nQTQy9jYSbRfqgnQVFCP) canvas.

## 2. Today

The header shows each node as a chip with its last heartbeat as "12 s ago". The header only loads
on a reload, so the age grows stale and a healthy node reads as "50 s ago". The heartbeat age was
removed from the chips first; this spec replaces the chips.

## 3. Scope

In scope:

- A Nodes tab next to Jobs and Events, with the node count and "N offline" while a node misses its
  heartbeats. The node chips leave the header.
- A node list (name, short id, online or offline, started, last heartbeat, running jobs) and a node
  detail (heartbeat age, uptime, the running jobs with links), both refreshed every 3 seconds.
- An offline node's detail says that other nodes release its jobs and later remove it.
- A running job's Node field links to its node.
- Core: `NodeInfo.runningJobs`, `Admin.findNode(UUID)`, `JobQuery.withNode(UUID)`.

Not in scope: the job types a node handles (nodes do not store them), recently finished jobs per
node, removing a node from the page.

## 4. Design

### Core

- `NodeInfo(id, name, startedAt, lastHeartbeatAt, alive, runningJobs)`: `runningJobs` counts the
  jobs with `locked_by` = the node (index `bikesched_jobs_locked_by_idx`).
- `Admin.findNode(UUID)` returns `Optional<NodeInfo>`, empty once the node is removed.
- `JobQuery.withNode(UUID)` filters `findJobs` on `locked_by`, so the running jobs of a node page
  like any other job list.

### API

- `GET api/nodes` gains `runningJobs`; `GET api/nodes/{id}` answers `{"node": {...}}` or `404`.
- `GET api/jobs?node={id}` lists the jobs a node runs; a malformed id answers `400`.

### Page

- Route `#/nodes/{id}`; the tab returns to the last node like the other tabs.
- The list and the open node poll every 3 seconds while the tab is visible, like a job's detail.
- The API keeps `alive`; the page shows it as ONLINE or OFFLINE, never "dead".

Rejected: clickable node chips in the header (no room on a phone, and the header still would not
refresh); polling the node list from the header on every page (a request every few seconds for a
detail most views do not show).

## 5. Test plan

- Core: `findNode` and `runningJobs` while a job runs and after it ends; `withNode` lists only that
  node's running jobs; an unknown id is empty.
- Admin: `api/nodes/{id}` and `api/jobs?node=` with a running job, `404` for an unknown node,
  `400` for malformed ids; the JSON contract of a node.
- Page: checked in a browser against the admin example with an extra offline node, light and dark,
  phone width.

## 6. Decisions

- A third tab, not clickable chips (board, open question 1).
- No job types per node until nodes record them (open question 2).
- No finished jobs per node: the page is about now (open question 3).

## 7. Decided while implementing

- The refresh interval is 3 seconds, the job detail's, not the 5 seconds of the board.
- The detail shows the running jobs once, as a list, instead of a count field and the list; an
  offline node shows its start without an uptime.
- The signed-in user and "Read only" stay in the header where the node chips were.
