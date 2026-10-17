---
title: "40. Nanojson for admin JSON with an optional shaded artifact"
weight: 40
---

# 40. Nanojson for admin JSON with an optional shaded artifact

## Status

Accepted. Supersedes the JSON writer and dependency choices of
[ADR 0038](0038-admin-ui.md); its other decisions remain unchanged.

## Context

The admin backend needs JSON response serialization, not payload object mapping. Maintaining a
custom JSON writer duplicates escaping and JSON handling provided by small libraries. Applications
may already depend on a different nanojson version and should be able to isolate the admin's copy.

## Decision

Use nanojson in `bikesched-admin` as an implementation dependency. Keep Java value normalization
internal; no nanojson types appear in the public API. Core's dependencies and payload mapping remain
unchanged.

Publish both `bikesched-admin` and `bikesched-admin-shaded`. The latter bundles only nanojson and
relocates it to `org.bikesched.admin.web.internal.nanojson`. Core and SLF4J remain external
dependencies. Use a separate artifact rather than a classifier so its Maven POM and Gradle
metadata can omit nanojson without changing the regular artifact's dependency graph.

## Consequences

- JSON escaping and serialization are delegated to nanojson instead of maintained locally.
- Consumers choose one admin artifact; the shaded version can coexist with application nanojson.
- The regular jar retains its JPMS descriptor. The shaded jar is an automatic module with the same
  name, avoiding a descriptor that requires the now-bundled nanojson module.
- Both publications retain core as an API dependency, sources, Javadoc and the admin UI resources.
- Admin tests run against both the regular classes and the actual shaded jar.
