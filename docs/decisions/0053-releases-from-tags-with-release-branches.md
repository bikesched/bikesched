---
title: "53. Releases cut from tags, one release branch per minor"
weight: 53
---

# 53. Releases cut from tags, one release branch per minor

Date: 2026-10-21

## Status

Accepted

## Context

bikesched had only snapshots of `main` on GitHub Packages (ADR 0015). The first release needs a
repeatable process: a version that cannot be mistyped, patches for a released version while `main`
moves on, a changelog of every change between two versions, and docs that show the version a
reader can actually use.

Considered for the process: release-please (a standing release PR that commits version and
changelog, but decides the version from the commits and has no Gradle support), Release Drafter
and GitHub's generated release notes (both only see pull requests, while many commits are pushed
to `main` directly), git-cliff, and JReleaser. For branches: a release branch per minor, per major
(`1.x`), or created only when a patch is needed. For the version: tag-based Gradle plugins (axion,
reckon) or bump plugins (gradle-release), which either move the version out of the version catalog
or commit and tag themselves.

## Decision

- `main` always holds the next minor as a snapshot in `gradle/libs.versions.toml`
  (`0.2.0-SNAPSHOT`). A manual workflow (`.github/workflows/release.yml`) cuts releases: major or
  minor from `main`, patch from `release/X.Y.x`. The version is derived from the catalog by the
  Gradle task `releaseVersions` (jsemver, in `build-logic`), the build gets it as
  `-PreleaseVersion`; there is no release commit. The workflow tags the built commit `vX.Y.Z`.
- A major or minor release creates its maintenance branch `release/X.Y.x` from the tag, at
  `X.Y.1-SNAPSHOT`; only the latest minor gets patches. A branch per major (`N.x`) is added only
  once `main` works on the next major while `N.y` minors still ship.
- Releases and snapshots are published to GitHub Packages for now; Maven Central comes later.
- JReleaser (Gradle plugin, configured in `bikesched.release`) creates the GitHub Release with a
  changelog generated from the Conventional Commits since the previous release of the line,
  breaking changes first; build, CI, test, chore, refactoring and style commits are left out. The
  GitHub Releases are the only changelog: no `CHANGELOG.md` in the repository, so a patch needs no
  commit on `main`. A pull request title check keeps squash-merged commits conventional.
- After a release the workflow commits the version bumps directly: dependency snippets in
  `README.md` and `docs/` show the version of their branch (the release on `release/X.Y.x`, the
  snapshot on `main`), so the website can show the latest release and the snapshot under `/next/`.
- From the first release on, the database schema changes only through new migrations
  (`V002__…`), also in a breaking 0.x minor: users have data in the released schema.

## Consequences

- Releasing is one click per release with a dry run that shows the version and the changelog; the
  `release` environment can require an approval before anything is published.
- JReleaser's Gradle plugin needs JGit 5, while Spotless brings JGit 7 onto the same plugin
  classpath. `build-logic` excludes JGit from Spotless, which only needs it to read line endings
  from `.gitattributes`; Spotless uses fixed LF line endings instead (the repository has no
  `.gitattributes`, and all sources use LF).
- The workflow pushes with a deploy key (secret `RELEASE_DEPLOY_KEY` in the `release`
  environment), which the rulesets protecting `main`, `release/**` and `v*` tags list as a bypass
  actor; `GITHUB_TOKEN` cannot bypass rulesets. Unlike `GITHUB_TOKEN` pushes, its pushes start
  workflows, so the version bumps start the snapshot builds and the website deploy.
- Fixes land on `main` first and are cherry-picked to the release branch.
- Moving to Maven Central later adds signing and a JReleaser deployer to the same workflow.
