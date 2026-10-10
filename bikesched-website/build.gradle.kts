plugins {
    alias(libs.plugins.hugo)
}

description = "Project site for bikesched.org, built with Hugo (docs/specs/website-project-site.md)"

// The Hextra theme (docs/specs/website-project-site.md §3) is a git submodule. A plain clone or
// worktree checkout leaves it as an empty directory, which Hugo silently accepts, rendering every
// page empty instead of failing. Fail fast with instructions instead.
val checkHugoTheme by tasks.registering {
    description = "Fails fast when the Hextra theme git submodule isn't checked out."
    val themeDir = layout.projectDirectory.dir("site/themes/hextra").asFile
    doFirst {
        if (themeDir.listFiles()?.isEmpty() != false) {
            throw GradleException(
                "$themeDir is empty: the Hextra theme git submodule isn't checked out. Run " +
                    "'git submodule update --init --recursive' and retry."
            )
        }
    }
}

tasks.matching { it.name in setOf("hugo", "hugoBuild", "hugoServer") }.configureEach {
    dependsOn(checkHugoTheme)
}

// hugo.toml mounts ../../README.md and ../../docs into the site's content tree (outside this
// module's own directory), so the hugoBuild task's default input tracking never sees them. Without
// this, Gradle's build cache (restored across CI runs by gradle/actions/setup-gradle) can replay a
// stale FROM-CACHE output after only README.md or docs/ changed, silently deploying old content.
tasks.named("hugoBuild") {
    inputs.file(rootProject.layout.projectDirectory.file("README.md"))
    inputs.dir(rootProject.layout.projectDirectory.dir("docs"))
}

// The Hextra theme ships default placeholder logo artwork (images/logo.svg, images/logo-dark.svg)
// that Hugo's module mount system has no clean way to exclude per-file without also dropping the
// theme's other implicit mounts (assets, layouts, i18n, data) and breaking the whole site (tried;
// see git history). Nothing references them (the navbar uses images/bikesched-logo.svg, mounted
// from docs/logo.svg instead), so delete them from the published output after the build.
val removeUnusedThemeLogos by tasks.registering(Delete::class) {
    delete(
        layout.buildDirectory.file("hugo/publish/images/logo.svg"),
        layout.buildDirectory.file("hugo/publish/images/logo-dark.svg"),
    )
}

tasks.named("hugoBuild") {
    finalizedBy(removeUnusedThemeLogos)
}
