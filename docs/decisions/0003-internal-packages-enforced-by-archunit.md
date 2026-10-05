# 3. Implementation in internal packages, enforced by ArchUnit

Date: 2026-10-05

## Status

Accepted

## Context

The public API should consist of interfaces, records and enums only, so the implementation can
change freely. A separate `api` package was considered.

## Decision

The API lives in `org.bikesched`, `org.bikesched.job` and `org.bikesched.event`; every
implementation (`Default<X>`) lives in `org.bikesched.internal` and its subpackages, which the JPMS
module does not export. The API reaches the implementation only through `internal.Internals`.
`ArchitectureTest` (ArchUnit) enforces that public signatures never mention internal types and that
dependencies between the internal packages only point downwards.

## Consequences

- No `api` package; the rules are checked by tests instead of package names.
- Moving or splitting internal packages needs an update of the layer rules.
