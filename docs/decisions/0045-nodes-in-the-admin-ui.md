---
title: "45. Nodes in the admin UI"
weight: 45
---

# 45. Nodes in the admin UI

Date: 2026-10-14

## Status

Accepted

## Context

The admin page showed the nodes as chips in its header, with the age of their last heartbeat. The
header only loads on a reload, so the age grew stale and a healthy node read as "50 s ago".
Operators also could not see what a node was running, e.g. before stopping it or when it went
silent.

Alternatives considered:

- Clickable node chips: no room on a phone, and the header would still not refresh.
- Polling the nodes from the header on every view: a request every few seconds for something most
  views do not show.
- A separate admin endpoint for a node's jobs: `findJobs` already pages and filters jobs; a node is
  one more filter.

## Decision

- The page gets a Nodes tab next to Jobs and Events. Its list and the open node refresh every 3
  seconds while visible; the header keeps only the tab with the node count and "N offline".
- `NodeInfo` gains `runningJobs`, `Admin` gains `findNode(UUID)` and `JobQuery` gains
  `withNode(UUID)` (jobs locked by the node). The API serves `api/nodes/{id}` and
  `api/jobs?node=`.
- The page says online and offline; the API and `NodeInfo` keep `alive`.

## Consequences

- A heartbeat age is only shown where it refreshes.
- `JobQuery` has one more component, so code calling its canonical constructor changes.
- The job types a node handles are not shown: nodes do not store them yet.
