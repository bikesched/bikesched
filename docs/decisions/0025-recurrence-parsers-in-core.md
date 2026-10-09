---
title: "25. Recurrence parsers in core"
weight: 25
---

# 25. Recurrence parsers in core

Date: 2026-10-07

## Status

Accepted

## Context

`@JobHandler(schedule = …)` took a string that `bikesched-annotations` parsed itself: ISO-8601
durations built in, other formats through `ScheduleProvider` arguments passed to every
`JobHandlers.scan`/`register` call (the Spring starter passed its beans). Recurrences as strings
were thus only available to annotations, although schedules from configuration (properties, one
entry per job) are useful without them, and a cron module implementing `ScheduleProvider` had to
depend on the annotations module.

Alternatives considered:

- Keep the SPI in the annotations module: cron and other formats stay annotation-only.
- A parse method on `Bikesched` returning a `Recurrence` (`schedule(bikesched.parse("…"))`):
  composes with every `schedule(Recurrence, …)` overload, but is one more entry point than needed.
- `schedule(String)` overloads also with a first run (`Instant`, `Duration`): not needed yet.

## Decision

Core has a `RecurrenceParser` SPI (`org.bikesched.job`: `supports(expression)`,
`parse(expression)`) and `JobBuilder.schedule(String recurrence)`, which parses the expression with
the node's parsers and registers the recurring job like `schedule(Recurrence)`. An expression must
be supported by exactly one parser, otherwise it is rejected with an `IllegalArgumentException`.

`BikeschedBuilder.recurrenceParsers(…)` replaces the node's parsers; the default is
`RecurrenceParser.isoDuration()` (ISO-8601 durations as `Recurrence.every`, fixed rate), so users
configure only the formats they use.

`@JobHandler` keeps its `schedule` attribute: scanning keeps the expression
(`AnnotatedJobHandler.schedule()`), registering calls `schedule(String)`. `ScheduleProvider` and the
provider arguments of `JobHandlers` are removed. The Spring starter configures ISO-8601 durations,
its Spring `CronExpression` parser and every `RecurrenceParser` bean, before the customizers run.

## Consequences

- String recurrences work the same with and without annotations; `bikesched-cron` depends on core
  only.
- An unsupported or invalid annotation schedule fails when the handler is registered, no longer when
  it is scanned. The handler itself is registered first, so the node keeps it; the failure still
  stops the startup.
- The parsers are a node setting: all code scheduling on a node shares one set of formats.
