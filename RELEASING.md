# Releasing bikesched

Releases are cut by the [release workflow](.github/workflows/release.yml), never from a local
checkout. Background and alternatives:
[ADR 0053](docs/decisions/0053-releases-from-tags-with-release-branches.md).

## Branches and versions

| Branch | Version in `gradle/libs.versions.toml` | Releases |
|---|---|---|
| `main` | the next minor as a snapshot, e.g. `0.2.0-SNAPSHOT` | major, minor |
| `release/X.Y.x` | the next patch as a snapshot, e.g. `0.1.3-SNAPSHOT` | patch |

- Releases are tags `vX.Y.Z` on the commit that was built; there is no release commit.
- A major or minor release creates `release/X.Y.x` from its tag. Only the latest minor gets
  patches; older release branches stay as history.
- A branch per major (`1.x`) is created only once `main` works on the next major while 1.y minors
  should still ship. Minors are then released from `1.x` (the workflow does not support this yet).
- Releases and snapshots are published to GitHub Packages. Every build of `main` and of a release
  branch publishes its snapshot.

## Cutting a release

1. Actions, Release, Run workflow: pick the branch (`main` for major and minor, `release/X.Y.x` for
   a patch), the release type, and first a **dry run**. Its summary shows the version and the
   changelog.
2. Run it again without dry run. With required reviewers on the `release` environment, approve the
   `release` job.

The workflow then

1. builds and tests the release (`./gradlew build -PreleaseVersion=X.Y.Z`) and publishes it to
   GitHub Packages,
2. tags the commit `vX.Y.Z` and creates the GitHub Release with the changelog (JReleaser's Gradle
   plugin, configured in `build-logic/src/main/kotlin/bikesched.release.gradle.kts`),
3. for a major or minor: creates `release/X.Y.x` (catalog `X.Y.1-SNAPSHOT`, docs showing
   `X.Y.Z`) and moves `main` on to the next minor snapshot; for a patch: moves the release branch
   on to the next patch snapshot,
4. adds the changelog to `CHANGELOG.md` on `main` and on the release branch,
5. starts the builds publishing the new snapshots and the website deploy.

The version is derived, never typed: minor and patch release the catalog version without
`-SNAPSHOT`, major releases the next major (this is how 1.0.0 gets cut). To see what a release
would produce:

```shell
./gradlew -q releaseVersions -PreleaseType=minor
```

## Changelog

The changelog lists the [Conventional Commits](https://www.conventionalcommits.org) between the
previous release of the line and the new tag (commits pushed directly to `main` included):
breaking changes (`!` or a `BREAKING CHANGE:` footer) first, then features, fixes, performance
and documentation. Build, CI, test and chore commits, refactorings and style changes are left out;
the compare link at the end covers them. Pull request titles are checked, since squash merges use
them as the commit message.

## Patches

Fix on `main` first, then cherry-pick the commit to the latest `release/X.Y.x` (a pull request
against the release branch, or `git cherry-pick -x` and a push) and run the release workflow on
that branch with type `patch`. Docs fixes for the current release go to the release branch the
same way.

## Rules from the first release on

- The database schema changes only through new migrations (`V002__…`), never by editing an
  applied one, also in a breaking 0.x minor.
- Patches contain fixes only, no API or schema changes.

## Setup

- Settings → Environments → `release`: add yourself as required reviewer.
- Branch protection or rulesets on `main`, `release/**` and `v*` tags must let GitHub Actions push:
  the workflow pushes the tag, the release branch and the version bumps with `GITHUB_TOKEN`.
