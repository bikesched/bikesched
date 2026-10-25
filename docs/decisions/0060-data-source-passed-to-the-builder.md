---
title: "60. Data source passed to the builder"
weight: 60
---

# 60. Data source passed to the builder

Date: 2026-10-25

## Status

Accepted

## Context

A node was built with `Bikesched.builder().dataSource(dataSource)…build()`. The data source is the
only required option, but nothing enforced it at compile time: a builder without one failed only
in `build()`, with an `IllegalStateException`. The Kotlin DSL inherited this with a nullable
`dataSource` property. Every caller (the Spring starter, `bikesched-test`, the examples, the docs)
set it first anyway.

Alternatives considered:

- `Bikesched.create(dataSource)…build()`, as db-scheduler does. Rejected: the method returns a
  builder, not a node, and `create` suggests a node ready to use.
- Keeping the setter next to a builder argument. Rejected: two ways to set the same option, and the
  API conventions ask for one fluent path with required options enforced by the compiler.

## Decision

`Bikesched.builder(DataSource)` replaces `Bikesched.builder()`, and `BikeschedBuilder` has no
`dataSource(…)` setter. The builder rejects a `null` data source with a `NullPointerException`.
The Kotlin DSL follows: `bikesched(dataSource) { … }`, with the configuration block optional.

## Consequences

- A missing data source is a compile error instead of a runtime failure.
- Breaking change for every caller (fine while at 0.x): `Bikesched.builder().dataSource(ds)`
  becomes `Bikesched.builder(ds)`, `bikesched { dataSource = ds }` becomes `bikesched(ds) { }`.
- A Spring `BikeschedCustomizer` can no longer replace the data source; the starter selects it
  (`bikesched.data-source`), and a user-defined `Bikesched` bean covers other cases.
