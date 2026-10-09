---
title: "22. Spring integration in its own module"
---

# 22. Spring integration in its own module

Date: 2026-10-06

## Status

Accepted

## Context

Spring Boot applications want a `Bikesched` node from their context — no builder code, handlers as
`@JobHandler` methods of beans — but core's defining property is its two runtime dependencies (the
PostgreSQL driver and `slf4j-api`), enforced by `AGENTS.md` ("core stays free of concrete
libraries"). Spring also brings problems core should not carry: AOP-proxied handler beans,
auto-configuration ordering against the application's `DataSource`, and lifecycle coupling
polling to the context.

## Decision

The integration lives in its own module, `bikesched-spring-boot-starter` (package and JPMS module
`org.bikesched.spring`), the same pattern as `bikesched-jackson`, `bikesched-annotations` and
`bikesched-micrometer`: it depends on core and annotations (`api`) plus `spring-boot-autoconfigure`;
core keeps no Spring type. One artifact, not the Boot convention of a separate `-autoconfigure`
module: the auto-configuration classes live in `org.bikesched.spring`, so a later split is
transparent to users. Jackson, Micrometer and `spring-tx`/`spring-jdbc` stay optional
(`compileOnly`/`requires static`, guarded by nested `@ConditionalOnClass` configurations).

## Consequences

- Applications that do not use Spring pay nothing; Spring upgrades touch only this module.
- The module is the project's first dependency on a large framework: Boot version support (4.1
  only) now has to be tracked, and Boot's own modularization (e.g. `DataSourceAutoConfiguration`
  moving to `spring-boot-jdbc`) is absorbed here — auto-configuration ordering is by name, not by
  class, to keep the dependency optional.
- Proxy-safe handler discovery required the additive `JobHandlers.scan(Class)`/`bindTo` API in
  `bikesched-annotations` rather than Spring-specific reflection in this module, so the validation
  logic exists once.
- Design details (discovery via a collecting `BeanPostProcessor`, fail-fast migration, the
  `<application-name>-<host>` default node name) are specified in
  [`docs/specs/bikesched-spring-boot-starter.md`](../specs/bikesched-spring-boot-starter.md) and
  documented in [`docs/spring.md`](../spring.md).
