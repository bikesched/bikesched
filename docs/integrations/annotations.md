---
title: "Annotations"
weight: 3
---

# Annotations

The module `bikesched-annotations` declares job handlers as methods annotated with `@JobHandler`
and [failure handlers](../jobs/README.md#failure-handlers) as methods annotated with
`@JobFailureHandler`, e.g. for dependency injection containers — inside a Spring context the
[bikesched-spring-boot-starter](spring.md) finds and registers them automatically. It depends on
`bikesched-core` only.

```java
public final class MailJobs {
    @JobHandler(name = "send-mail")
    public void sendMail(Job job, Mail mail) { … }

    @JobHandler(name = "archive-mail")
    public void archiveMail(Mail mail) { … }

    @JobHandler(name = "check-mailbox")
    public void checkMailbox() { … }

    @JobFailureHandler(name = "send-mail")
    public void sendMailFailed(FailedJob failed, Mail mail) { … }
}

JobHandlers.register(bikesched, new MailJobs());
```

## Method signatures

- The method is `public`, not `static`, and returns `void`.
- It has at most two parameters, in any order: the `Job` and the payload. Both are optional, so
  `(Job, Mail)`, `(Mail)`, `(Job)` and `()` are valid.
- The payload parameter's class is the payload type: the node's `JsonMapper` maps the payload to it,
  like for a `TypedJob`. It must be a non-generic class (e.g. a record, not `List<Mail>`) and not a
  primitive; see [Payload design](../getting-started/configuration.md#payload-design).
- Exceptions thrown by the method fail the attempt like those of any job handler: they are passed
  on unchanged, so retries and `last_error` see the original exception.
- Annotated methods of superclasses are found too; each job name may be handled by only one method.
- A `@JobFailureHandler` method follows the same rules with a `FailedJob` instead of the `Job`:
  `(FailedJob, Mail)`, `(Mail)`, `(FailedJob)` and `()`. Its exceptions make the failure handler
  run again. Each job name may have only one, and a method can't carry both annotations.

Invalid methods are rejected when the object is scanned, with an `IllegalArgumentException` naming
the method and the problem, not when a job runs.

## Registering

| Method | Does |
|---|---|
| `JobHandlers.register(bikesched, target)` | registers all annotated methods of `target`; returns them, job handlers first, then failure handlers |
| `JobHandlers.scan(target)` | finds and checks them without registering; each `AnnotatedJobHandler` has `name()`, `isFailureHandler()`, `payloadType()`, `schedule()`, `fixedDelay()`, `initialDelay()`, `method()` and `registerOn(bikesched)` |

A method with a payload parameter is registered for `TypedJob.of(name, payloadClass)`, so the node
rejects a typed job with the same name and another payload class. Methods without one are
registered for the name, like `registerJobHandler(String, JobHandler)`. Failure handler
methods are registered with `registerJobFailureHandler` the same way.

In a module path application, the method's package must be open to the module
`org.bikesched.annotation` (or the class public in an exported package).

Recurring handlers declare a recurrence expression with `schedule`, e.g.
`@JobHandler(name = "cleanup", schedule = "PT1H")`. Registering the handler also registers the
recurring job with `newJob(name).schedule(expression)`, so the node's recurrence parsers parse it,
by default ISO-8601 durations at a fixed rate; see
[Recurrence expressions](../jobs/recurring-jobs.md#recurrence-expressions). Scanning only keeps the
expression (`schedule()`): an unsupported or invalid one fails the registration. With
`bikesched-cron`'s parser on the node, handlers can use cron expressions:

```java
Bikesched bikesched = Bikesched.builder(dataSource)
        .recurrenceParsers(RecurrenceParser.isoDuration(),
                new CronRecurrenceParser(CronType.UNIX, ZoneId.of("Europe/Berlin")))
        .build();
JobHandlers.register(bikesched, new ReportJobs());
// in ReportJobs:
@JobHandler(name = "report", schedule = "0 3 * * MON-FRI")
public void report() { … }
```

`fixedDelay` declares a [fixed delay](../jobs/recurring-jobs.md#fixed-delay) instead, an ISO-8601
duration: `@JobHandler(name = "sync-crm", fixedDelay = "PT15M")` registers the recurring job with
`Recurrence.everyAfterEnd(PT15M)`, so each run starts 15 minutes after the previous one ended.
`initialDelay` (an ISO-8601 duration, e.g. `PT0S` to run right away) delays a new first occurrence
of a recurring handler, like `schedule(recurrence, delay)`; registering again (on a restart) keeps
the pending occurrence. Scanning parses both and rejects an invalid duration, a `fixedDelay` that
is not positive, a negative `initialDelay`, a method with both `schedule` and `fixedDelay`, and an
`initialDelay` without either.
