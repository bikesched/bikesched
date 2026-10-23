---
title: "55. Byte limits for names and keys"
weight: 55
---

# 55. Byte limits for names and keys

Date: 2026-10-23

## Status

Accepted

## Context

[ADR 0006](0006-text-columns-with-length-checks.md) limits names to 200 characters and keys to
500 so that index entries stay below PostgreSQL's B-tree maximum of 2704 bytes. A character takes
up to 4 bytes in UTF-8, and three indexes combine a name with a key
(`bikesched_jobs_idempotency_key_idx`, `bikesched_events_name_key_idx`,
`bikesched_steps_waiting_idx`): 200 × 4 + 500 × 4 = 2800 bytes. A name and a key of 4-byte
characters (emoji, for example) at the limit passed validation and then failed at the insert with
`index row size 2824 exceeds btree version 4 maximum 2704`.

## Decision

Names and keys also have at most 3 bytes per character of their limit in UTF-8: 600 bytes for
names, 1500 for keys. `ValidationUtil` checks both limits (a name or key over the byte limit gets
an `IllegalArgumentException` naming it), and the CHECK constraints add `octet_length`. An index
on a name and a key stays at most 2100 bytes.

## Consequences

- Text of the Basic Multilingual Plane (every common script) still gets the full character
  limit; only characters of 4 bytes count more.
- New name or key columns need the byte limit in their CHECK too.
