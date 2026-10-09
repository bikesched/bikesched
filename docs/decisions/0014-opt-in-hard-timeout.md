---
title: "14. Opt-in hard timeout for handlers that ignore their timeout"
weight: 14
---

# 14. Opt-in hard timeout for handlers that ignore their timeout

Date: 2026-10-05

## Status

Accepted

## Context

A timeout only interrupts the handler's thread; a handler stuck in non-interruptible code keeps
its slot and its `RUNNING` row forever.

## Decision

With `hardTimeoutGrace(Duration)`, an attempt whose handler has not returned that long after its
timeout is abandoned: recorded as failed like a timeout, its slot freed, its thread's stack trace
logged. The thread keeps running; the lock token (ADR 0010) rejects its later writes and its
outcome is discarded. It is off by default.

## Consequences

- Off by default because an abandoned attempt and its retry can run at the same time, repeating
  side effects outside the database, and abandoned threads keep their resources.
- Recommended for idempotent handlers with timeouts.
