# Architecture decision records

Significant design decisions of bikesched, one file per decision, numbered in order, in the
format of Michael Nygard (Status, Context, Decision, Consequences), see
[ADR 0001](0001-record-architecture-decisions.md). Copy [the template](template.md) for a new one.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-own-sql-migrations.md) | Own SQL migrations instead of a migration framework | Accepted |
| [0003](0003-internal-packages-enforced-by-archunit.md) | Implementation in internal packages, enforced by ArchUnit | Accepted |
| [0004](0004-typed-job-and-event-types.md) | Typed job and event types next to the string-based API | Accepted |
| [0005](0005-recurring-jobs-via-schedule-recurrence.md) | Recurring jobs via schedule(Recurrence) | Accepted |
| [0006](0006-text-columns-with-length-checks.md) | Text columns with length checks; messages truncated | Accepted |
| [0007](0007-job-history-in-separate-table.md) | Job history in a separate, opt-in table | Accepted |
| [0008](0008-event-expiry-fixed-at-publish.md) | Event expiry fixed when publishing | Accepted |
| [0009](0009-step-writes-fenced-by-job-lock.md) | Step writes fenced by the job's lock | Accepted |
| [0010](0010-lock-token-per-claim.md) | A lock token per claim instead of a version column | Accepted |
| [0011](0011-received-event-steps.md) | Received event steps store the mapped result | Superseded by [0018](0018-event-payload-copied-into-steps.md) |
| [0012](0012-job-retention-by-default.md) | Ended jobs deleted after a retention by default | Accepted |
| [0013](0013-statement-timeout-as-query-timeout.md) | Statement timeout as JDBC query timeout | Accepted |
| [0014](0014-opt-in-hard-timeout.md) | Opt-in hard timeout for handlers that ignore their timeout | Accepted |
| [0015](0015-snapshots-to-github-packages.md) | Snapshots published to GitHub Packages with maven-publish | Accepted |
| [0016](0016-claim-only-handled-job-types.md) | Nodes claim only job types they handle | Accepted |
| [0017](0017-retry-failed-jobs.md) | Retrying failed jobs keeps their completed steps | Accepted |
| [0018](0018-event-payload-copied-into-steps.md) | Event payloads copied into steps, no event handlers | Accepted |
| [0019](0019-annotations-module.md) | Job handler annotations in their own module | Accepted |
| [0020](0020-job-listener-and-micrometer.md) | Job listener SPI with Micrometer in its own module | Accepted |
| [0021](0021-apache-license-and-trademark-policy.md) | Apache License 2.0 with a trademark policy | Accepted |
| [0022](0022-spring-integration-in-its-own-module.md) | Spring integration in its own module | Accepted |
| [0023](0023-steps-cannot-be-nested.md) | Steps cannot be nested | Accepted |
| [0024](0024-cron-expressions-via-cron-utils.md) | Cron expressions via cron-utils in their own module | Accepted |
| [0025](0025-recurrence-parsers-in-core.md) | Recurrence parsers in core | Accepted |
| [0026](0026-durable-sleep-as-step.md) | Durable sleep as a step without an event | Accepted |
| [0027](0027-job-events.md) | Publish events from jobs as durable steps | Accepted |
| [0028](0028-step-kinds-and-event-references.md) | Step kinds and plain event references | Accepted |
| [0029](0029-child-jobs-as-steps.md) | Child jobs as one step holding the outcome | Accepted |
| [0030](0030-event-listener.md) | Event listener reports committed publications only | Accepted |
