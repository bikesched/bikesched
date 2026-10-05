# 10. A lock token per claim instead of a version column

Date: 2026-10-05

## Status

Accepted

## Context

Fencing by the claiming node (ADR 0009) cannot tell two claims of the same node apart: a node
that gets its released job back would accept the old attempt's writes. A general optimistic-locking
`version` column was considered; the `attempts` count is not unique (suspending gives an attempt
back).

## Decision

Every claim sets a new random `lock_token` on the job, cleared when the claim ends. All writes of
an attempt (ending the job, suspending it, steps, event waits) check `lock_token`; `locked_by` stays
for releasing a node's jobs and for diagnostics. In code, `JobLock(nodeId, token)` is passed to the
stores.

## Consequences

- Exactly the claim's writes are fenced; other legitimate changes of the job row (cancel
  requests, published events, releases) do not conflict as they would with a version column.
- One column, no retry loops.
