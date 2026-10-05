# 15. Snapshots published to GitHub Packages with maven-publish

Date: 2026-10-05

## Status

Accepted

## Context

Snapshot builds should be usable before a release. Candidates: Gradle's `maven-publish`, the
Vanniktech Maven Publish plugin (Maven Central), JReleaser.

## Decision

GitHub Actions builds every pull request and push to `main`; after a successful build of `main`,
a separate job publishes the snapshot of the library modules to GitHub Packages with
`publishAllPublicationsToGitHubPackagesRepository` (convention plugin `bikesched.publishing` with
Gradle's `maven-publish`).

## Consequences

- No third-party plugin for a plain authenticated upload; the Vanniktech plugin remains an option
  for releases to Maven Central.
- Reading the packages requires a GitHub token, also for public repositories.
