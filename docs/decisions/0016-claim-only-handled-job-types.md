# 16. Nodes claim only job types they handle

Date: 2026-10-05

## Status

Accepted

## Context

Nodes claimed every due job and failed an attempt for a type they had no handler for. During a
rolling deploy, nodes of the old version took jobs of types only the new version handles and used
up their attempts until they ended `FAILED`. db-scheduler leaves such "unresolved" tasks to other
instances.

## Decision

The claim only selects jobs whose `job_type` is one of the node's registered handler types
(`job_type = any(?)`). `job_type` becomes the last key column of the polling index
(`priority desc, scheduled_at, job_type`), so the condition is checked on index entries in claim
order, without reading the rows of skipped jobs (measured: 36 instead of about 5,400 buffers with
100,000 due jobs of an unhandled type ahead in claim order). No separate index per type: claiming
across types in priority order needs the order of the existing index.

## Consequences

- Jobs of a type no node handles stay `SCHEDULED` instead of failing; there is no automatic
  clean-up or warning for them yet.
- Many due jobs of unhandled types ahead in claim order still cost index reads on every poll.
