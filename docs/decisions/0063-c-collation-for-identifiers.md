---
title: "63. C collation for identifiers"
weight: 63
---

# 63. C collation for identifiers

Date: 2026-10-27

## Status

Accepted

## Context

The `text` columns of the schema hold machine identifiers: job, step, event and node names, event
keys, idempotency keys, references, recurrence descriptions, types, states and history actions.
They used the database's default collation, typically a linguistic one such as `en_US.UTF-8`.
That costs in three ways (schema review, item 6):

- Comparisons and sorts go through glibc or ICU, slower than byte comparison, in every index on
  these columns (polling, idempotency keys, recurring jobs, event lookups).
- A glibc or ICU upgrade can change the sort order and leaves these indexes silently corrupt until
  they are rebuilt (as with glibc 2.28).
- The admin's prefix filters on event keys (`starts_with`) can use a B-tree index only under the C
  collation.

After the first release the schema changes only through new migrations (ADR 0053); changing the
collation then rewrites the indexes on every user's database. Before it, V001 is edited in place.

Alternatives: keep the default collation (the costs above), or require a database created with
`LC_COLLATE=C` (a requirement on the user's database instead of the schema's own choice).

## Decision

Every `text` column of the bikesched tables except the messages (`last_error`, `cancel_message`)
is declared `collate "C"` in V001. Message columns are never compared or indexed and keep the
default collation.

## Consequences

- Index comparisons on identifiers are byte-wise and independent of the operating system's locale
  data.
- Lists the admin sorts by name are in byte order: upper case before lower case, non-ASCII after
  ASCII.
- Parameters and literals compared with these columns take the column's collation, so queries need
  no `collate` clauses.
