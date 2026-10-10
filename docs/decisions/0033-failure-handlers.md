# 0033. Failure handlers for jobs and steps

Date: 2026-10-10

## Status

Accepted

## Context

A job that fails permanently often needs a reaction: move it to a dead-letter table, mark an order
as failed, alert someone. The job handler can't do this reliably itself, because it doesn't run
after a timeout, an abandoned attempt or a crash. `JobListener.onAttemptEnded` reports the failure
only after the commit and only on the node that recorded it, so the report is lost if that node
dies. Step failure handlers existed, but they received the exception: the real one on the first run
and a `BikeschedException` built from the stored text on reruns. They could only react, not recover,
and a handler that kept failing gave the job extra attempts without limit.

The incidents spec (an operator inbox with incident jobs) addressed the same need with a new table
and an API to resolve incidents. It was put on hold in favour of this smaller mechanism.

## Decision

- A job type can have a `JobFailureHandler`. It can be registered with
  `registerJobFailureHandler`, with typed and class-based variants (`TypedJobFailureHandler`,
  `JobTypeFailureHandler`), or with `failureHandler(…)` on the job builders.
- The statements that end a job `FAILED` or `COMPENSATION_FAILED` make it `FAILING` instead when
  the failing node has a failure handler for the type. A new column, `failing_state`, holds the
  state it ends in.
- The failure handler runs in a claim of its own after the compensations, counted in
  `failure_attempts`. Once it returns, the job ends. If it fails, it runs again after the retry
  backoff, up to the node's `failureHandlerAttempts` runs (default 3). After that the job ends
  anyway.
- The job's outcome is final: the handler only reacts to it.
- Everything that happens when a job ends moves to the end of `FAILING`: the parent's child-job
  step completes and the next recurring occurrence is scheduled.
- Both handlers get the failure as stored text (`FailedJob.error()`, `FailingStep.error()`), never
  as an exception. They may run in a later attempt or on another node. A timeout, an abandoned
  attempt or a dead node has no exception, and exceptions don't round-trip through JSON.
- A step failure handler gets a `FailingStep<T>` and may `resolve(result)` it. The step then
  `SUCCEEDED` with that result, but it is not compensable, and the job continues.
- A step failure handler's runs count when they start (`bikesched_steps.failure_attempts`), so a
  run that crashes the node counts too. After `failureHandlerAttempts` runs the step is `FAILED`.
- A parent falls back on a failed child with `JobResult.orElse(fallback)`. A child's failure
  handler can't resolve the child.

Rejected:

- Running the job failure handler right after the failed attempt with the real exception. This
  needs two paths (inline, and a later claim after a crash), the inline one runs outside the
  attempt's timeout, and it can't run after compensation.
- Serializing exceptions.
- A flag column instead of the `FAILING` state: claiming, releasing and the lock CHECK all key on
  the state.

## Consequences

- Users build dead-letter tables and alerts with the guarantees of jobs: at least once, retried,
  within a timeout. The failure handler must be idempotent.
- The job and its steps have one more state, `FAILING`, which appears in `JobState`, the
  constraints, the poll and recurring indexes, and the job history. There are new listener outcomes
  (`FAILING`, `FAILURE_HANDLER_RETRYING`), and `JobClaim.failing()` is a new record component.
- The failure handler must be registered on every node handling the type: the failing node decides
  by its own registration. A node claiming a `FAILING` job without a failure handler ends the job.
- `StepFailureHandler` changed incompatibly (`handle(FailingStep<T>)`).
- Kotlin (`handleJobFailures`, `onFailure`), annotations (`@JobFailureHandler`) and the Spring
  starter support job failure handlers next to the job handlers.
