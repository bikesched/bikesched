---
title: "Operations"
weight: 6
---

# Operations

Running bikesched in production: looking into jobs, measuring them and knowing what is in the
database.

- [Admin UI](admin.md): a page for jobs, steps, events and nodes with retry and cancel
  (`bikesched-admin`), and the lookups behind it
- [Metrics](metrics.md): `JobListener`, `EventListener` and the Micrometer meters
  (`bikesched-micrometer`)
- [Database](database.md): tables, indexes and migrations

Deploying new handler code while jobs are in flight is covered in
[Changing handlers](../jobs/changing-handlers.md).
