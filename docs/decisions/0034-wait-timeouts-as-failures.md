---
title: "34. Timeouts of event waits and polls handled by failure handlers"
weight: 34
---

# 34. Timeouts of event waits and polls handled by failure handlers

Date: 2026-10-10

## Status

Accepted. Supersedes the timeout handlers of [ADR 0031](0031-poll-steps.md) (polls) and the
unguarded timeout handlers of [ADR 0023](0023-steps-cannot-be-nested.md) (event waits).

## Context

Event waits and polls took a `TimeoutHandler` (`T handle()`) with their timeout. Its result was not
stored: it ran on every replay that reached the timed-out wait, also during compensation, so it
needed no side effects of its own, and a fallback computed from e.g. the current time changed
between runs. It got no context, and a handler that threw failed the attempt, whose retry timed out
again. Steps already had failure handlers ([ADR 0033](0033-failure-handlers.md)): a `FailingStep`
with the stored error, counted runs, and a stored `resolve(result)`.

The alternatives were a durable timeout handler of its own (`onTimeout(timedOut ->
timedOut.resolve(x))`), which reads better for a timeout that is a business branch but repeats the
counting, storing and resolving under a second name, and storing the `TimeoutHandler`'s result,
which keeps the extra concept and the throwing ambiguity.

## Decision

bikesched handles a timeout as a failure of the wait or poll: `EventWait<T>` and `PollBuilder<T>`
take the step failure handler, `onFailure(StepFailureHandler<T>)`. `TimeoutHandler`,
`EventWait.Timed`, `TypedEventWait` and the `timeout(Duration, TimeoutHandler)` overloads are
removed.

- When a run reaches a wait or poll whose deadline passed, the step goes from `TIMED_OUT` to
  `FAILING` with the timeout exception's `toString()` as `last_error`, and the handler runs right
  away, in that attempt (a retry would time out again). From there it is the step failure
  handling: counted runs up to `failureHandlerAttempts`, `resolve` stores the fallback and the step
  is `SUCCEEDED`, otherwise the step is `FAILED` and the job fails without further attempts.
- A poll's handler also handles a check that throws in the job's last attempt, like a step's.
- The handler handles the node's `defaultEventTimeout` too. Without a handler nothing changes: the
  step stays `TIMED_OUT` and the job fails.
- The string-based `waitForEvent(step, name, key, Class)` takes the payload class, like `newStep`
  and `newPoll`, so `onFailure` is typed before `receive()`; `waitForEvent(step, EventType, key)`
  returns the same `EventWait<T>`.
- A resolved event wait is `SUCCEEDED` without an event; `bikesched_steps_event_check` tells it
  apart by `last_error`, which resolved steps keep. No new column or state.

## Consequences

- A fallback is stored, returned on later runs and compensation replays without running the handler
  again, and visible in the job history (`TIMED_OUT`, `FAILING`, `SUCCEEDED`).
- Like every failure handler, a timeout's handler runs inside the step guard: it can no longer run
  steps or polls. Falling back on a poll means resolving with `null` and polling after `receive()`.
- A timeout that is a normal business branch reads as `onFailure`; the docs present a timeout as
  one way a wait fails.
- Child job waits keep `JobResult.TimedOut` as a value: the parent decides, no handler.
