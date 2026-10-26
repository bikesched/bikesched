---
title: "Decisions"
weight: 7
---

# Architecture decision records

Significant design decisions of bikesched, one file per decision, numbered in order, in the
format of Michael Nygard (Status, Context, Decision, Consequences), see
[ADR 0001](0001-record-architecture-decisions.md). Copy [the template](template.md) for a new one.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-own-sql-migrations.md) | Own SQL migrations instead of a migration framework | Accepted |
| [0003](0003-internal-packages-enforced-by-archunit.md) | Implementation in internal packages, enforced by ArchUnit | Accepted |
| [0004](0004-typed-job-and-event-types.md) | Typed job and event types next to the string-based API | Accepted, renamed by [0059](0059-job-name-and-job-type.md) |
| [0005](0005-recurring-jobs-via-schedule-recurrence.md) | Recurring jobs via schedule(Recurrence) | Accepted |
| [0006](0006-text-columns-with-length-checks.md) | Text columns with length checks; messages truncated | Accepted |
| [0007](0007-job-history-in-separate-table.md) | Job history in a separate, opt-in table | Superseded by [0062](0062-one-history-with-actions-and-actors.md) |
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
| [0022](0022-spring-integration-in-its-own-module.md) | Spring integration in its own module | Accepted; startup migration superseded by [0039](0039-optional-schema-migration.md) |
| [0023](0023-steps-cannot-be-nested.md) | Steps cannot be nested | Accepted, timeout handlers superseded by [0034](0034-wait-timeouts-as-failures.md) |
| [0024](0024-cron-expressions-via-cron-utils.md) | Cron expressions via cron-utils in their own module | Accepted |
| [0025](0025-recurrence-parsers-in-core.md) | Recurrence parsers in core | Accepted |
| [0026](0026-durable-sleep-as-step.md) | Durable sleep as a step without an event | Accepted |
| [0027](0027-job-events.md) | Publish events from jobs as durable steps | Accepted |
| [0028](0028-step-kinds-and-event-references.md) | Step kinds and plain event references | Accepted |
| [0029](0029-child-jobs-as-steps.md) | Child jobs as one step holding the outcome | Accepted |
| [0030](0030-event-listener.md) | Event listener reports committed publications only | Accepted |
| [0031](0031-poll-steps.md) | Poll steps: one step for all checks | Accepted, timeout handler superseded by [0034](0034-wait-timeouts-as-failures.md) |
| [0032](0032-batched-notifications-after-commit.md) | Waking pollers with batched notifications after the commit | Accepted |
| [0033](0033-failure-handlers.md) | Failure handlers for jobs and steps | Accepted |
| [0034](0034-wait-timeouts-as-failures.md) | Timeouts of event waits and polls handled by failure handlers | Accepted |
| [0035](0035-website-hosted-on-static-app.md) | Website hosted on static.app instead of GitHub Pages | Superseded by [0047](0047-website-hosted-on-cloudflare-workers.md) |
| [0036](0036-idempotency-keys.md) | Idempotency keys as columns, taken while the job exists | Accepted |
| [0037](0037-job-postpone.md) | Postponing a job gives its attempt back | Accepted |
| [0038](0038-admin-ui.md) | Admin UI as static files on a framework-neutral handler | Accepted, JSON handling superseded by [0040](0040-admin-nanojson-and-shaded-artifact.md) |
| [0039](0039-optional-schema-migration.md) | Optional automatic schema migration with history validation | Accepted |
| [0040](0040-admin-nanojson-and-shaded-artifact.md) | Nanojson for admin JSON with an optional shaded artifact | Accepted |
| [0041](0041-jspecify-nullness-annotations.md) | Nullness of the public API with JSpecify annotations | Accepted, partly superseded by [0048](0048-nullaway-in-the-core-build.md) |
| [0042](0042-removed-nodes-register-again.md) | Removed nodes register again under their id | Accepted, amended by [0051](0051-node-names-are-not-unique.md) |
| [0043](0043-sealed-types-for-internal-job-state.md) | Sealed types for the internal job state | Accepted |
| [0044](0044-publishing-events-from-the-admin-ui.md) | Publishing events from the admin UI | Accepted |
| [0045](0045-nodes-in-the-admin-ui.md) | Nodes in the admin UI | Accepted |
| [0046](0046-scheduling-jobs-from-the-admin-ui.md) | Scheduling jobs from the admin UI | Accepted |
| [0047](0047-website-hosted-on-cloudflare-workers.md) | Website hosted on Cloudflare Workers instead of static.app | Accepted |
| [0048](0048-nullaway-in-the-core-build.md) | Nullness checked with NullAway in the core build | Accepted |
| [0049](0049-java-21-baseline.md) | Java 21 as the language baseline, built with JDK 26 | Accepted |
| [0050](0050-load-and-failure-tests-on-demand.md) | Load and failure tests in their own module, run on demand | Accepted |
| [0051](0051-node-names-are-not-unique.md) | Node names are not unique | Accepted |
| [0052](0052-clean-ups-delete-in-resuming-batches.md) | Clean-ups delete in resuming batches | Accepted |
| [0053](0053-releases-from-tags-with-release-branches.md) | Releases cut from tags, one release branch per minor | Accepted |
| [0054](0054-time-ordered-ids.md) | Time-ordered ids (UUIDv7) | Accepted |
| [0055](0055-byte-limits-for-names-and-keys.md) | Byte limits for names and keys | Accepted |
| [0056](0056-schema-naming-column-order-and-invariants.md) | Schema naming, column order and invariants | Accepted |
| [0057](0057-steps-keyed-by-a-time-ordered-id.md) | Steps keyed by a time-ordered id | Accepted (history part superseded by [0062](0062-one-history-with-actions-and-actors.md)) |
| [0058](0058-fixed-delay-recurrences-and-planned-runs.md) | Fixed-delay recurrences and a planned run on every occurrence | Accepted |
| [0059](0059-job-name-and-job-type.md) | Job name and job type | Accepted |
| [0060](0060-data-source-passed-to-the-builder.md) | Data source passed to the builder | Accepted |
| [0061](0061-references-for-jobs-and-events.md) | References for jobs and events | Accepted |
| [0062](0062-one-history-with-actions-and-actors.md) | One history with actions and actors | Accepted |
