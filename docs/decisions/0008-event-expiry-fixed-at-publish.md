---
title: "8. Event expiry fixed when publishing"
---

# 8. Event expiry fixed when publishing

Date: 2026-10-05

## Status

Accepted

## Context

Events are kept so that steps starting to wait later still receive them. Nodes may have
different retention settings, and single events need their own retention (also zero).

## Decision

The expiry is stored with the event (`expires_at` = published + retention of the event or of the
publishing node) when it is published. Waits only receive unexpired events; the clean-up deletes
expired ones. Events are not marked as matched: steps reference the event they received.

## Consequences

- All nodes agree on an event's lifetime.
- `retention(Duration.ZERO)` delivers only to the steps waiting right now.
