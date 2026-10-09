---
title: "7. Job history in a separate, opt-in table"
weight: 7
---

# 7. Job history in a separate, opt-in table

Date: 2026-10-05

## Status

Accepted

## Context

A job has one row whose state is overwritten; past states are lost, which makes debugging hard.
Alternatives: a `jsonb` array of changes on the job row, a listener SPI, database triggers.

## Decision

Changes of jobs and their steps are appended to `bikesched_job_history` in the transaction of
the change, by the stores through `HistoryStore`, only if the node enables it
(`jobHistory(retention)`); a clean-up deletes entries after the retention. No foreign keys: entries
outlive their jobs.

## Consequences

- No extra writes or row growth on the busy jobs table when disabled; no rewrite of a growing
  array on every change when enabled.
- Nodes without the history leave gaps; it should be enabled on all nodes.
