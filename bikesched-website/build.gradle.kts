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
