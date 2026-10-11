# Specifications

Design specs written before a feature is implemented (see `AGENTS.md`, section Documentation),
oldest first. The creation date is the date of the commit that added the spec.

| Created | Spec | Status | Decision |
|---|---|---|---|
| 2026-10-06 | [Spring Boot starter](bikesched-spring-boot-starter.md) | implemented | [ADR 0022](../decisions/0022-spring-integration-in-its-own-module.md) |
| 2026-10-07 | [Durable sleep in jobs](job-sleep.md) | implemented | [ADR 0026](../decisions/0026-durable-sleep-as-step.md) |
| 2026-10-07 | [Publishing events from jobs](job-events.md) | implemented | [ADR 0027](../decisions/0027-job-events.md) |
| 2026-10-07 | [Child jobs and job results](child-jobs.md) | implemented | [ADR 0029](../decisions/0029-child-jobs-as-steps.md) |
| 2026-10-07 | [Event listener](event-listener.md) | implemented | [ADR 0030](../decisions/0030-event-listener.md) |
| 2026-10-08 | [Poll steps](poll-step.md) | implemented | [ADR 0031](../decisions/0031-poll-steps.md) |
| 2026-10-08 | [Incidents for permanently failed jobs](incidents.md) | on hold | — |
| 2026-10-08 | [Waking pollers with LISTEN/NOTIFY](listen-notify.md) | implemented | [ADR 0032](../decisions/0032-batched-notifications-after-commit.md) |
| 2026-10-09 | [Project website (bikesched.org)](website-project-site.md) | implemented | [ADR 0035](../decisions/0035-website-hosted-on-static-app.md) |
| 2026-10-10 | [Failure handlers for jobs and steps](failure-handlers.md) | implemented | [ADR 0033](../decisions/0033-failure-handlers.md) |
| 2026-10-10 | [Failure handlers for event waits and polls](wait-failure-handlers.md) | implemented | [ADR 0034](../decisions/0034-wait-timeouts-as-failures.md) |
| 2026-10-11 | [Idempotency keys for jobs and events](idempotency-keys.md) | implemented | [ADR 0036](../decisions/0036-idempotency-keys.md) |
| 2026-10-11 | [Postponing jobs](job-postpone.md) | implemented | [ADR 0037](../decisions/0037-job-postpone.md) |
| 2026-10-11 | [Admin UI](admin-ui.md) | draft | — |
