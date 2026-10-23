// Release tooling of the root project (RELEASING.md, ADR 0053), driven by the release workflow
// (.github/workflows/release.yml):
//
// - `releaseVersions -PreleaseType=major|minor|patch` prints the version to release and the
//   snapshots the branches continue with
// - `jreleaserChangelog` / `jreleaserRelease` (with `-PreleaseVersion=…`, `-PpreviousTag=…`,
//   `-PreleaseBranch=…`) generate the changelog and create the GitHub Release for the tag the
//   workflow pushed
// - `setVersion -PnewVersion=… [-PdocsFrom=… -PdocsTo=…]` writes the version bumps back after the
//   release

import org.bikesched.gradle.ReleaseVersionsTask
import org.bikesched.gradle.SetVersionTask
import org.jreleaser.model.Active

plugins {
    // JReleaser hooks into `clean`.
    base
    id("org.jreleaser")
}

val libs = versionCatalogs.named("libs")

tasks.register<ReleaseVersionsTask>("releaseVersions") {
    group = "release"
    description = "Prints the version of the next release (-PreleaseType=major|minor|patch)."
    currentVersion = libs.findVersion("bikesched").get().requiredVersion
    releaseType = providers.gradleProperty("releaseType")
}

tasks.register<SetVersionTask>("setVersion") {
    group = "release"
    description =
        "Sets the version in the catalog (-PnewVersion) and in the docs (-PdocsFrom, -PdocsTo)."
    newVersion = providers.gradleProperty("newVersion")
    docsFrom = providers.gradleProperty("docsFrom")
    docsTo = providers.gradleProperty("docsTo")
    catalog = layout.projectDirectory.file("gradle/libs.versions.toml")
    // The pages published on the website; ADRs and specs keep the version they were written for.
    docs.from(
        layout.projectDirectory.file("README.md"),
        fileTree("docs") {
            include("**/*.md")
            exclude("decisions/**", "specs/**")
        },
    )
}

// The changelog lists the Conventional Commits (AGENTS.md, section Commits) since the previous
// release of the same line (`-PpreviousTag`, none for the first release), grouped by type with
// breaking changes first. Build, CI, test and chore commits, refactorings and style changes are
// left out; the compare link at the end covers them.
val previousTag = providers.gradleProperty("previousTag").orNull?.takeIf { it.isNotBlank() }

jreleaser {
    project {
        description = "A PostgreSQL-backed job scheduler for Java"
        authors = listOf("Simon Büttner")
        license = "Apache-2.0"
        inceptionYear = "2026"
        links {
            homepage = "https://bikesched.org"
        }
    }
    release {
        github {
            repoOwner = "bikesched"
            name = "bikesched"
            tagName = "v{{projectVersion}}"
            releaseName = "{{tagName}}"
            // The workflow tags the commit it built and pushes the tag itself.
            skipTag = true
            previousTag?.let { previousTagName = it }
            branch = providers.gradleProperty("releaseBranch").getOrElse("main")
            overwrite = false
            milestone {
                close = false
            }
            changelog {
                formatted = Active.ALWAYS
                preset = "conventional-commits"
                skipMergeCommits = true
                links = true
                format = "- {{commitShortHash}} {{commitTitle}}"
                categoryTitleFormat = "### {{categoryTitle}}"
                content =
                    "{{changelogChanges}}{{#previousTagName}}**Full changelog**: " +
                        "{{repoUrl}}/compare/{{previousTagName}}...{{tagName}}\n" +
                        "{{/previousTagName}}"
                contributors {
                    enabled = false
                }
                labeler {
                    label = "breaking"
                    title = "regex:^\\w+(\\([^)]*\\))?!:.*"
                    order = -1
                }
                labeler {
                    label = "breaking"
                    body = "regex:(?s).*BREAKING[ -]CHANGE:.*"
                    order = -1
                }
                category {
                    title = "🚨 Breaking changes"
                    key = "breaking"
                    labels = listOf("breaking")
                    order = 1
                }
                category {
                    title = "Refactorings"
                    key = "refactorings"
                    labels = listOf("refactor", "style")
                    order = 25
                }
                hide {
                    categories = listOf("refactorings", "merge", "test", "tasks", "build")
                }
            }
        }
    }
}
