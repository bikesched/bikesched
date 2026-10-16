# Optional schema migration

Status: implemented. [ADR 0039](../decisions/0039-optional-schema-migration.md);
[configuration](../getting-started/configuration.md), [database
guide](../operations/database.md#migrations).

## Goal and usage

Let an application disable automatic DDL while retaining bikesched's migration bookkeeping.

```java
var scheduler = Bikesched.builder()
    .dataSource(dataSource)
    .migrateSchema(false)
    .autoStartPolling(false)
    .build();
```

## Existing behavior

The lazy database initializer migrates on first database use. Polling enabled at build time
triggers it immediately. `Bikesched.migrate()` currently calls that same initializer.

## Scope and dependencies

Core builder flag, Spring property and validation, tests and docs; no new dependencies. No external
Flyway/Liquibase integration, configurable schema or physical schema drift detection. Kotlin callers
can use the Java builder; a Kotlin DSL property is outside this change.

## Design and decisions

- `migrateSchema(boolean)` defaults to `true`; `false` validates on the same lazy boundary.
- Validation reads versions/checksums in `bikesched_schema_migrations` in one query. It issues
  no DDL or writes, creates no history table and takes no migration advisory lock. Deployment
  must finish migrations before application access; a racing validator can fail and be retried.
- Every bundled version must be present with its bundled checksum. Unknown versions warn and
  are ignored, matching the existing migrator's rolling-deployment policy.
- Missing history, missing versions and checksum mismatches fail with actionable messages.
  Read failures retain their SQL cause and identify the history table and required access.
- Share checksum comparison with migration, not its DDL setup. Do not infer compatibility from
  table existence alone, or silently manufacture migration history.
- Explicit `Bikesched.migrate()` always migrates, even with the flag disabled or after successful
  validation. Serialize it with lazy initialization; success marks initialization complete,
  while failure is retryable. It never registers or starts the node.
- Spring exposes only `bikesched.migrate-schema`, default `true`, wired to the core builder. The
  starter makes no explicit migration call: polling triggers migration or validation at startup;
  without polling it waits for first database use. No separate startup-migration property or alias.
- No public validation method or new exception type: use the existing migration exception
  (`BikeschedException` subtype). Validation is migration history validation, not schema auditing.

## Test plan and implementation steps

1. Add integration tests first, using isolated schemas in the shared PostgreSQL container.
2. Prove missing history fails without creating objects, initialized schemas work without DDL,
   missing versions and mismatches fail without writes, unknown versions warn, explicit migration
   works before and after validation, lazy retries work, and the default still migrates.
3. Wire the builder/Settings flag into the initializer and add read-only history validation.
4. Test Spring binding/defaults, lazy initialization without polling, startup migration/validation
   with polling, validation-only failure without DDL, and explicit user migration.
5. Update configuration/database/Spring docs, API Javadoc, ADR and indexes; trim the backlog.
6. Run Spotless and the full Gradle build, counting XML test results.

## Open questions

None. The bookkeeping contract and unknown-version policy above are decided.
