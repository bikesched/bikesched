plugins {
    id("bikesched.java-library")
}

description = "Admin UI and JSON API for bikesched"

// Preact and htm for the page: copied from their WebJars into the jar's resources at build time,
// so they are no runtime dependency of the published module.
val webjars = configurations.create("webjars") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    api(project(":bikesched-core"))
    implementation(libs.slf4j.api)
    webjars(libs.webjars.preact)
    webjars(libs.webjars.htm)

    testImplementation(libs.postgresql)
}

interface InjectedArchives {
    @get:Inject val archives: ArchiveOperations
}

// The ES modules and licenses, flattened into ui/vendor/. hooks.module.js imports the bare
// specifier "preact", which a browser cannot resolve without an import map (an inline script the
// CSP forbids), so it is rewritten to the relative file.
val vendorUi = tasks.register<Sync>("vendorUi") {
    val archives = objects.newInstance<InjectedArchives>().archives
    from(webjars.elements.map { jars -> jars.map { archives.zipTree(it.asFile) } }) {
        include(
            "META-INF/resources/webjars/preact/*/dist/preact.module.js",
            "META-INF/resources/webjars/preact/*/hooks/dist/hooks.module.js",
            "META-INF/resources/webjars/preact/*/LICENSE",
            "META-INF/resources/webjars/htm/*/dist/htm.module.js",
            "META-INF/resources/webjars/htm/*/LICENSE",
        )
        eachFile {
            val library = relativePath.segments[3]
            path = "org/bikesched/admin/web/ui/vendor/" +
                if (name == "LICENSE") "$library-license.txt" else name
        }
        filter { line: String -> line.replace("from\"preact\"", "from\"./preact.module.js\"") }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/vendor-resources"))
}

// The project's logo (docs/logo.svg, see TRADEMARKS.md) as the page's icon and header logo, copied
// so there is one source for the README, the website and the admin.
val logoUi = tasks.register<Sync>("logoUi") {
    from(rootProject.layout.projectDirectory.file("docs/logo.svg")) {
        into("org/bikesched/admin/web/ui")
    }
    into(layout.buildDirectory.dir("generated/logo-resources"))
}

sourceSets.main {
    resources.srcDir(vendorUi)
    resources.srcDir(logoUi)
}
