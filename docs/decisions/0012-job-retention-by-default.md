---
title: "12. Ended jobs deleted after a retention by default"
weight: 12
---

# 12. Ended jobs deleted after a retention by default

Date: 2026-10-05

## Status

Accepted

## Context

Ended jobs and their steps were never deleted, so the tables grew without bound.

## Decision

Every polling node deletes ended jobs (`SUCCEEDED`, `FAILED`, `CANCELLED`) with their steps once
they finished longer than the job retention ago, 7 days by default (`jobRetention`), in batches of
1000. `COMPENSATION_FAILED` jobs are kept, they need attention. The history has its own
retention.

## Consequences

- Bounded tables without configuration; a very long retention keeps jobs practically forever.
- `findJob` returns empty for deleted jobs.
