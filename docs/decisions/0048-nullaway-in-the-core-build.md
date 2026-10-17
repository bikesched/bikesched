---
title: "48. Nullness checked with NullAway in the core build"
weight: 48
---

# 48. Nullness checked with NullAway in the core build

Date: 2026-10-16

## Status

Accepted

## Context

[ADR 0041](0041-jspecify-nullness-annotations.md) marked the exported packages of
`bikesched-core` with JSpecify, but left the internal packages unmarked and the build without a
checker. The implementation therefore guarded itself: most internal constructors and records
started with `Objects.requireNonNull` for every argument, although the values came from other
internal code that never passes `null`. Which internal fields, parameters and record components may
be `null` was only stated in Javadoc, and a missing `@Nullable` on the API was only caught in
review.

The alternatives were to keep checking with NullAway now and then by hand, or to use the Checker
Framework's nullness checker, which is stricter but much slower and needs more annotations
(initialization, map keys).

## Decision

bikesched checks nullness at compile time with [Error Prone](https://errorprone.info/) and
[NullAway](https://github.com/uber/NullAway) in JSpecify mode, applied to `bikesched-core` by the
convention plugin `bikesched.errorprone`.

- Every package of `bikesched-core` is `@NullMarked`, the internal ones included. Fields, parameters,
  returns and record components that may be `null` are `@Nullable`.
- NullAway checks only `@NullMarked` code (`OnlyNullMarked`); unannotated libraries are trusted. A
  NullAway finding is a compile error.
- Error Prone's default checks run too; their warnings fail the build like other compiler warnings
  (`-Werror`). Test sources are not checked.
- Internal code does not check for `null` what the types already exclude: no `requireNonNull` on
  arguments coming from other internal code. Checks stay at the API boundary, where callers may be
  unchecked code (user arguments of `Bikesched`, `Job` and the builders, user SPIs returning values).
- `Sql.Row` is `@NullUnmarked`: whether a column getter returns `null` depends on the schema, which
  the types cannot see. Rows map NOT NULL columns into non-null fields and nullable columns into
  `@Nullable` ones.
- `checker-qual` is a compile-only dependency of core: NullAway reads the package annotations of the
  PostgreSQL driver, which refer to it.

## Consequences

- Dereferencing a possibly `null` value, or passing `null` where it is not allowed, fails the build
  instead of surfacing as a `NullPointerException` at runtime, and a missing `@Nullable` on the API
  is caught when its implementation passes `null`.
- About 140 null checks of internal arguments are gone, and the nullness of internal state is part
  of its type instead of its Javadoc.
- Compiling core takes longer (Error Prone runs as a javac plugin), and new code must keep the
  annotations correct; a value NullAway cannot follow (a field read twice) needs a local variable.
- Follow-up: the other Java modules are not checked yet (`IDEAS.md`).
