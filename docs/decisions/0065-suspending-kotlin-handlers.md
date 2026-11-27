---
title: "65. Suspending Kotlin handlers"
weight: 65
---

# 65. Suspending Kotlin handlers

Date: 2026-11-27

## Status

Accepted

## Context

Kotlin backends call suspending APIs (Ktor client, coroutine-based database libraries). The
lambdas of `bikesched-kotlin` (`handleJobs`, `step`, `poll`, compensations, failure handlers)
were plain functions, so users wrapped every such call in `runBlocking` themselves.

Every attempt runs on its own virtual thread (`JobPoller`), and the job relies on that thread: the
MDC of the job, the check against nested steps (`StepGuard`) and the `Error` that ends an attempt
at an event wait, sleep or poll (`JobSuspended`). A job's timeout interrupts the thread
(`JobAttempt`). The Java callback interfaces (`JobHandler`, `StepHandler`) are synchronous.

Alternatives considered:

- A separate `bikesched-kotlin-coroutines` module keeping `bikesched-kotlin` free of
  kotlinx-coroutines: a suspend lambda and a plain lambda do not overload one function cleanly, so
  it needs a second name for every operation, two styles per operation.
- Dispatching handlers to a coroutine dispatcher (`Dispatchers.IO` or a dispatcher of the node):
  the coroutine would leave the job's thread, losing the MDC and moving `JobSuspended` and the
  job's interrupt to another thread.
- Suspending event waits, sleeps and polls without blocking: they already end the attempt and hold
  no thread while waiting, so nothing is gained.

## Decision

Every lambda of the Kotlin DSL is a suspend lambda. bikesched runs each with `runBlocking` on the
calling thread, the job's virtual thread; a step, poll or compensation runs in a nested
`runBlocking` on the same thread. `bikesched-kotlin` depends on `kotlinx-coroutines-core`
(`api`).

The interrupt of the job's timeout or of a stopping node cancels the coroutine: `runBlocking`
throws `InterruptedException`, which the job handles like an interrupted blocking handler. Code
ignoring cancellation is still abandoned by the hard timeout.

## Consequences

- Handlers call suspending functions directly; existing handlers written as lambda literals
  compile unchanged. Function references of non-suspend functions and compiled callers need a
  rebuild (a binary break, fine at 0.x).
- `bikesched-kotlin` no longer depends on the Kotlin standard library only.
- `Job` methods must be called from the handler's own coroutine, not from another dispatcher
  (`withContext(Dispatchers.IO)`) or a coroutine launched into another scope; coroutines launched
  in the handler's scope pause while a step runs, since the nested `runBlocking` blocks the thread.
