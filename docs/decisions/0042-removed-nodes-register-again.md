---
title: "42. Removed nodes register again under their id"
weight: 42
---

# 42. Removed nodes register again under their id

Date: 2026-10-13

## Status

Accepted

## Context

Other nodes remove a node without heartbeat for twice the node timeout, after releasing its jobs.
A node that was only paused (a long garbage collection, a frozen VM) or cut off from the database
resumes afterwards with its row gone. It kept heartbeating into nothing and every claim failed on
the foreign key of `bikesched_jobs.locked_by`: the node was alive, but never ran a job again. Its
handlers' job types starved if no other node handled them.

Alternatives considered: register under a new id (its in-memory id would have to change while
attempts run under the old one), or shut the node down (the application would have to restart
it).

## Decision

When a heartbeat finds the node's row gone, the node registers again with its id and name
(`insert … on conflict do nothing`). If another node holds the name by then, it does not take it
over and logs a warning on every heartbeat.

## Consequences

- A resumed node works again within one heartbeat interval; its stale attempts are still fenced by
  their lock tokens.
- A name taken over by another node in the meantime leaves the resumed node unregistered, as
  before; it needs a restart.
