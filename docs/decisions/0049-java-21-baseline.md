# 0049. Java 21 as the language baseline, built with JDK 26

Date: 2026-10-16

## Status

Accepted

## Context

bikesched was compiled for Java 26, the JDK it is built with, so applications had to run on Java
26 too. Most teams run the long-term support releases, today mostly Java 21, and cannot move to a
non-LTS release for one library. bikesched's own code needed little beyond Java 21: virtual
threads, records, sealed types and pattern matching for `switch` are all final in 21. The only
newer feature in use was unnamed variables (`_`, final in Java 22); no JDK API newer than 21 was
used, and none of the dependencies (Spring Boot 4, Jackson 3, Micrometer, Kotlin) needs more.

Alternatives considered:

- Java 22 as the baseline: keeps `_`, but 22 is a non-LTS release without public updates since
  September 2024. Teams that cannot run the newest Java stay on an LTS release, so 22 would add
  almost no users.
- Java 25, the current LTS release: needs no code changes, but still excludes the teams on 21.
- Building with JDK 21 too: would make the toolchain and the release the same version, but the
  website's Hugo Gradle plugin needs Gradle to run on Java 25 or later.

## Decision

bikesched supports Java 21 and later. The build keeps JDK 26 as its toolchain (`java` in
`gradle/libs.versions.toml`) and compiles for the baseline (`java-release`): javac with
`--release 21`, Kotlin with `jvmTarget` 21 and `-Xjdk-release=21`. Both reject language features
and JDK APIs newer than 21 at compile time, so the baseline cannot erode by accident.

Unnamed variables are replaced: unused lambda parameters, patterns and catch variables are named
`ignored`, resources that only scope a block get a descriptive name (`logContext`, `mdc`), and
`case A _, B _` labels become one case per type. The `try` lint is disabled, since such resources
are never referenced in their block.

CI runs the tests on JDK 21 (`-PtestJavaVersion=21`), so they exercise the oldest supported
runtime rather than the one the build runs on.

## Consequences

- Applications on Java 21 can use bikesched; the published Gradle module metadata announces JVM
  version 21.
- Java 22 and later features (unnamed variables, flexible constructor bodies, module imports,
  Markdown Javadoc comments, stream gatherers, scoped values) are unavailable until the baseline
  is raised, which needs a new ADR.
- Code with several unnamed patterns in one `case` gets slightly longer.
- The tests no longer run on JDK 26 in CI; the build still compiles with it.
