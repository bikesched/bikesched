---
title: "6. Text columns with length checks; messages truncated"
weight: 6
---

# 6. Text columns with length checks; messages truncated

Date: 2026-10-05

## Status

Accepted

## Context

Names and keys are indexed `text` columns. Unbounded values could exceed PostgreSQL's B-tree
entry limit; error and cancel messages can be arbitrarily long.

## Decision

Names (node, job type, step, event, recurrence description) are limited to 200 characters, event
keys to 500, checked by `ValidationUtil` where they are passed in and by CHECK constraints in the
schema. Messages (`last_error`, `cancel_message`) have no CHECK: the stores truncate them to 4000
characters, so recording a failure can never fail on its length. The recurrence description is
validated, not truncated, because it is compared for equality.

## Consequences

- Clear `IllegalArgumentException`s instead of database errors.
- New name or key columns need both the CHECK and the validation.
