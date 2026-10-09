---
title: "23. Steps cannot be nested"
weight: 23
---

# 23. Steps cannot be nested

Date: 2026-10-07

## Status

Accepted

## Context

A step handler could call `job.step(...)` or wait for an event itself; nothing prevented it. The
semantics were unclear: the inner step completed durably even if the outer step then failed, a
retry ran the outer step again but skipped the inner one (returning its stored result), and a wait
inside a step suspended the job while the outer step was still `RUNNING`. The same held for
compensations, which run after the compensation replay and would hit its replay logic.

Alternatives considered:

- Allow nesting and document inner steps as independent durable steps. Keeps flexibility, but
  every user has to understand the partial-replay semantics, and a wait inside a step has no
  sensible meaning.
- Guard only the step handler's own thread (a `ThreadLocal`). Keeps parallel sibling steps from
  the handler's own threads working, but a step handler handing work to another thread could still
  nest.

## Decision

bikesched rejects nesting. While a step handler or a compensation runs, the job (a flag per job,
set from any thread) throws an `IllegalStateException` for `step(...)`, `newStep(...)`,
`StepBuilder.run(...)`, `waitForEvent(...)`, `awaitEvent(...)` and `receive` of a wait created
earlier. Thrown inside a step handler it fails that step like any exception.

Timeout handlers of event waits are not guarded: they are not steps (they run on every run that
reaches the timed-out wait), and running a step there is the way to make a fallback with side
effects durable.

## Consequences

- Every step completes or fails as a whole; a retry repeats exactly the failed step.
- Steps of one job cannot run in parallel from several threads: the second one throws.
- Existing handlers that nested steps fail their outer step and need to run the steps one after
  the other.
