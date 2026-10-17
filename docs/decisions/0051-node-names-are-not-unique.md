---
title: "51. Node names are not unique"
weight: 51
---

# 51. Node names are not unique

Date: 2026-10-17

## Status

Accepted

## Context

`bikesched_nodes` had a unique constraint on the node name. A node registering under a name held
by a live node failed to start; one held by a dead node (no heartbeat for the node timeout) was
taken over together with its id, and the jobs still locked by that id were released at once.

An instance that crashed or was killed (no deregistration) and restarted within the node timeout,
30 seconds by default, still found its previous row live and failed to start. That is the normal
case for a supervisor or orchestrator restarting a crashed process under the same host name, and
for two applications on one host relying on the default name.

Nothing else depends on the name: claims, lock tokens, heartbeats, the node clean-up, the job
history and the admin UI's links are keyed by the node id. The constraint guarded against a
configuration mistake that does no harm, two live nodes with one name, and turned it into a
startup failure.

Alternatives considered: reclaiming a recently-heartbeating row with the same name (cannot tell a
crashed predecessor from a live duplicate, so it would fence a running node), or waiting for the
timeout on startup (slows every restart after a crash).

## Decision

bikesched drops the unique constraint. Every node registers under a new id with a plain insert;
its name only labels it in logs and the admin UI. A restarted node no longer takes over the row of
its previous run: that row times out and the node clean-up releases its jobs and removes it like
any dead node. A node removed while still running registers again under its id (ADR 0042) without
a name check. The admin UI links a running job to its node by id (`JobSummary.nodeId`).

## Consequences

- An instance restarted after a crash starts right away instead of failing.
- Jobs left running by a crashed instance are released after the node timeout, not on the restart
  of an instance with the same name.
- Two live nodes may share a name; logs and the admin UI tell them apart by id and start time.
