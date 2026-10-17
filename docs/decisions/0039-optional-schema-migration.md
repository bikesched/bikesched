---
title: "39. Optional automatic schema migration with history validation"
weight: 39
---

# 39. Optional automatic schema migration with history validation

## Status

Accepted. Supersedes the startup-migration behavior described by
[ADR 0022](0022-spring-integration-in-its-own-module.md).

## Context

Application database users may not have DDL rights. The existing lazy initializer always migrates,
and `Bikesched.migrate()` uses that initializer too. Disabling migration must not silently accept
an incompatible schema or disable the explicit deploy-time migration path.

## Decision

Add the core builder flag `migrateSchema(boolean)`, defaulting to `true`. With `false`, lazy
initialization reads `bikesched_schema_migrations` instead of migrating. Every bundled version
must have its original checksum. Unknown versions warn and are ignored, preserving the migrator's
rolling-deployment policy. Missing history/versions and mismatches fail with corrective guidance.

Validation performs only a history query, without DDL, history writes or a migration advisory
lock. Deployments must finish migrations before applications access the database. Failed lazy
initialization remains retryable; successful initialization is cached per instance.

Explicit `Bikesched.migrate()` always invokes the migrator, regardless of the flag or a previous
successful initialization. It is serialized with initialization and marks it complete on success.
It does not register or start the node.

Spring exposes the same flag as `bikesched.migrate-schema`, default `true`. Remove the separate
startup-migration property and the starter's explicit migration call, with no compatibility alias
(the starter is unreleased). Polling triggers core initialization at startup; scheduling-only
instances defer it until first database use. Applications may still call `Bikesched.migrate()`
explicitly when they need deployment-time migration.

Retain bikesched's bookkeeping contract. Table-only checks cannot prove migration compatibility;
external migration adapters and configurable schemas are separate work.

## Consequences

Applications can run without DDL privileges after a separate migration-capable deploy step.
Validation does not detect physical schema drift or wait for a concurrent deploy to finish.
Applying SQL through another tool without bikesched's history is not sufficient. Core gains no
runtime dependencies or schema changes. Scheduling-only Spring applications no longer contact the
database at startup unless application code uses it.

See the [spec](../specs/optional-schema-migration.md) and
[database guide](../operations/database.md#migrations).
