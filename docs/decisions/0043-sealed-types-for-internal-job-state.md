---
title: "43. Sealed types for the internal job state"
weight: 43
---

# 43. Sealed types for the internal job state

Date: 2026-10-14

## Status

Accepted

## Context

The implementation in `org.bikesched.internal` carried the state of claimed jobs, steps and
attempts as strings, nullable columns and boolean flags. A claimed job had `failingState`,
`failureAttempts`, `compensatedState`, `parentJobId`, `recurrence` and `fixedRateAt`, whose valid
combinations only the processor knew; step rows compared `kind` and `state` strings; store results
such as `PollStep` or `EventWait` were one record with a state string and fields valid only in
some states; `JobAttempt` tracked its lifecycle with three booleans. Every reader re-derived the
cases with `if` chains, and a missing case compiled.

`DefaultJob` also mixed two modes in each step primitive: running a handler and replaying it to
collect the compensations of a job claimed to be compensated.

## Decision

bikesched models these cases as types the compiler checks:

- `JobPhase` (`Run`, `Compensate`, `HandleFailure`) is what a claim is for, `JobOrigin` (`OneOff`,
  `Child`, `Occurrence`) where the job came from. The store maps the columns once, when claiming.
- `ClaimEnd` carries the job, its lock and a `FollowUp` (`None`, `NotifyParent`, `ScheduleNext`)
  that the ending write performs in its transaction.
- Store results with distinct outcomes are sealed interfaces (`ChildWait`, `PollStep`,
  `EventWaitResult`); step kinds and states are the enums `StepKind` and `StepState`, job states
  the public `JobState`.
- `JobProcessor` switches over a sealed `Verdict` of an attempt and its recorded outcome;
  `JobAttempt` has one state enum.
- `CompensationReplay` holds the replaying variant of every step primitive; `DefaultJob` delegates
  to it when the claim's phase is `Compensate`.

The schema is unchanged: the types are mapped from and to the existing columns.

## Consequences

- Exhaustive `switch` statements replace `if` chains; a new phase, origin, follow-up or store
  outcome fails to compile until every reader handles it.
- Fields exist only where they are valid, so nothing reads a `null` that means "not this case".
- Writes that end a claim take a `ClaimEnd`, so ending a child job or an occurrence of a recurring
  job cannot forget its follow-up.
- Store tests compare the typed results instead of strings.
