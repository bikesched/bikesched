# 19. Job handler annotations in their own module

Date: 2026-10-05

## Status

Accepted

## Context

A planned Spring integration should discover job handlers declared as annotated methods. Finding
and invoking such methods needs reflection, which core does not use, and plain Java applications
could use the same mechanism without Spring. The obvious annotation name `@Job` clashes with the
`org.bikesched.job.Job` interface, which annotated methods take as a parameter.

## Decision

A module `bikesched-annotations` (package `org.bikesched.annotation`, depending on core only)
provides `@JobHandler(type = …)` for public, non-static `void` methods with at most two
parameters: the `Job` and the payload, both optional, in any order. Any parameter other than `Job`
is the payload; its class must be non-generic and not primitive. `JobHandlers.scan(target)` checks
all annotated methods of an object (and its superclasses) and returns them; `register(bikesched,
target)` also registers them, typed (`JobType.of(type, payloadClass)`) if the method has a payload
parameter. Methods are invoked via `MethodHandle`, so exceptions reach bikesched unwrapped.
Recurring jobs and job settings are not part of the annotation yet.

## Consequences

- Core stays free of reflection; the Spring module only adds bean discovery and lifecycle.
- Signature errors fail at startup, not when a job runs.
- On the module path, users open the handler packages to `org.bikesched.annotation`.
