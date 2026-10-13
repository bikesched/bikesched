# 0041. Nullness of the public API with JSpecify annotations

Date: 2026-10-13

## Status

Accepted

## Context

Public API signatures did not say which parameters and return values may be `null`: callers had to
read the Javadoc ("may be `null`"), and Kotlin saw every Java type as a platform type. Null is a
real value in several places (payloads, step results, cancel messages, the optional columns of the
admin records), so `Optional` everywhere is no option, and wrapping parameters in it is not
idiomatic Java anyway.

[JSpecify](https://jspecify.dev/) is the common standard for nullness annotations, understood by
IntelliJ IDEA, NullAway, the Checker Framework and the Kotlin compiler. Its jar contains only
annotations.

## Decision

bikesched marks the exported packages of `bikesched-core` with `@NullMarked`: types without an
annotation are non-null, and `@Nullable` marks exactly the places where `null` is a valid value.

- Type parameters of user values (payloads, step and poll results, event payloads, child results;
  `JobType<T>`, `StepHandler<T>`, `JobBuilder<P>`, …) are declared `<T extends @Nullable Object>`,
  so they accept nullable type arguments (e.g. Kotlin's `step<String?>`).
- Positions where `null` occurs whatever the type argument are `@Nullable T`: a job without
  payload, a step returning `null`, `FailingStep.resolve(null)`, `JobResult.orElse(null)`.
- `Optional`-returning lookups stay as they are.
- `org.jspecify:jspecify` is an `api` dependency of core (`requires transitive org.jspecify`), as
  JSpecify recommends, so annotations are visible to consumers' tools and to Kotlin.
- The internal packages and the other modules are not marked yet, and the build has no nullness
  checker; the annotations are verified once with NullAway in JSpecify mode, which reports no
  errors on the marked packages.

## Consequences

- IDEs and checkers warn about passing `null` where it is not allowed and about dereferencing
  values that may be `null`.
- Kotlin sees real nullability instead of platform types: e.g. `job.payload()` is `String?`, and a
  Java callback interface implemented in Kotlin gets its payload as `T?`. This is a source change
  for Kotlin code implementing `TypedJobHandler`, `FailingStep` and similar interfaces directly.
- New API signatures must keep the annotations correct; without a checker in the build, reviews
  have to catch a missing `@Nullable`.
- Follow-up: mark the internal packages and the other modules, and decide whether to enforce the
  annotations with NullAway in the build (`IDEAS.md`).
