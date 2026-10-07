# 24. Cron expressions via cron-utils in their own module

Date: 2026-10-07

## Status

Accepted

## Context

Recurring jobs run on a `Recurrence`; core offers `every`, `daily`, `weekly` and custom
recurrences, and recurrence expressions given as strings are parsed by the node's
`RecurrenceParser`s (ADR 0025). Users expect cron expressions. The Spring Boot starter parses
Spring's six-field `CronExpression`, evaluated in UTC; plain Java applications have no cron support,
and Spring applications can't choose another dialect or zone.

Alternatives considered:

- Spring's `CronExpression`: one dialect, pulls in `spring-context` for non-Spring users.
- A cron parser of our own in core: a dialect to maintain, against the "minimal core" rule.
- [cron-utils](https://github.com/jmrozanec/cron-utils) 9.2.1: Apache 2.0, Unix, Quartz, Spring and
  cron4j dialects, `ExecutionTime.nextExecution`, only `slf4j-api` as a dependency, an
  `Automatic-Module-Name` (`com.cronutils`). Its last release is from March 2023, though the
  repository is still maintained.

## Decision

A module `bikesched-cron` (package `org.bikesched.cron`) depends on core and cron-utils only (both
`api`; cron-utils' `CronType` is part of the API) and provides:

- `CronRecurrence`, a public `Recurrence`: `of(expression, zone)` for Unix expressions,
  `of(expression, CronType, zone)` for other dialects. Invalid expressions and expressions without a
  future execution are rejected on creation. The description is `cron <dialect> <normalized
  expression> <zone>`, so a changed expression, dialect or zone moves the pending occurrence. Not
  fixed rate.
- `CronRecurrenceParser`, a `RecurrenceParser` for one dialect and zone (Unix and UTC by default),
  for `schedule(String)` and `@JobHandler(schedule = …)`.

The Spring Boot starter keeps its Spring `CronExpression` parser and does not configure the
bikesched-cron one; applications that want it declare it as a `RecurrenceParser` bean with their
dialect and zone, which is added to the built-in parsers.

## Consequences

- Plain Java, annotation and Spring users share one cron implementation, with the dialect and zone
  chosen explicitly.
- Daylight saving time follows cron-utils: a non-existent local time is skipped that day, a repeated
  one runs once for a fixed hour and twice for a wildcard hour; tests pin this down.
- In Spring, a bikesched-cron parser whose dialect overlaps with the built-in six-field syntax
  (`SPRING`, `SPRING53`, `QUARTZ` without a year) makes such expressions ambiguous: registering
  fails with "Multiple RecurrenceParsers support recurrence". Unix (five fields) doesn't overlap.
- The normalized expression comes from cron-utils (`asString()`); if an upgrade changes it, pending
  occurrences are moved once to the same schedule.
