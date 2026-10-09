---
title: "5. Recurring jobs via schedule(Recurrence)"
---

# 5. Recurring jobs via schedule(Recurrence)

Date: 2026-10-05

## Status

Accepted

## Context

Recurring jobs had their own builder (`recurring(...)`). Scheduling a one-off job and
registering a recurring one are the same gesture with a different "when".

## Decision

`JobBuilder.schedule(...)` is overloaded by argument type: `Instant`/`Duration` schedule a
one-off job, `Recurrence` (also with a start `Instant`/`Duration`) registers a recurring job,
idempotently, and returns its pending occurrence. The first occurrence is the next run after now.
`TransactionalJobBuilder` has no recurrence overload.

## Consequences

- One fluent path for all scheduling; the compiler separates the variants.
- Occurrences are ordinary job rows carrying their `recurrence` description.
