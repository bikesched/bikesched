# 2. Own SQL migrations instead of a migration framework

Date: 2026-10-05

## Status

Accepted

## Context

The schema has to be created and evolved in the application's database. Flyway or Liquibase
would add a dependency (with its own versioning and licensing) to a library whose runtime
dependencies are deliberately limited to the PostgreSQL driver and the SLF4J API.

## Decision

bikesched applies plain SQL migrations from `org/bikesched/migrations` with its own small
`SchemaMigrator` (applied versions with checksums in `bikesched_schema_migrations`). While
unreleased, schema changes go into `V001` in place.

## Consequences

- No extra dependency; migrations run on first use of a node.
- Features of migration frameworks (repeatable migrations, undo, ...) are not available.
- Until the first release, local databases must be recreated after schema changes.
