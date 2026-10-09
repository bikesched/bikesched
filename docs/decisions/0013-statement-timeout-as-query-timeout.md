---
title: "13. Statement timeout as JDBC query timeout"
---

# 13. Statement timeout as JDBC query timeout

Date: 2026-10-05

## Status

Accepted

## Context

A hanging connection (e.g. after a failover without TCP reset) can block the poller or a job
forever. PostgreSQL's `statement_timeout` is a session setting.

## Decision

The optional node-wide `statementTimeout(Duration)` is applied as JDBC query timeout to every
statement, in whole seconds (rounded up). Without it, the data source decides.

## Consequences

- No session state leaks into pooled connections and no extra round trip; also covers
  statements on a caller's connection.
- Only second granularity.
