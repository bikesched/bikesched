plugins {
    id("bikesched.java-library")
    alias(libs.plugins.shadow)
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
    implementation(libs.nanojson)
    shadow(project(":bikesched-core"))
    shadow(libs.slf4j.api)
    webjars(libs.webjars.preact)
    webjars(libs.webjars.htm)

    testImplementation(project(":bikesched-test"))
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

// Bundle only nanojson. Core and SLF4J remain normal dependencies in both publications.
val shadedJson = configurations.create("shadedJson") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    shadedJson(libs.nanojson)
}

shadow {
    addShadowVariantIntoJavaComponent = false
}

tasks.shadowJar {
    archiveBaseName = "bikesched-admin-shaded"
    archiveClassifier = ""
    configurations = listOf(shadedJson)
    relocate("com.grack.nanojson", "org.bikesched.admin.web.internal.nanojson")
    // The regular descriptor requires nanojson; the shaded jar is an automatic module instead.
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
    manifest.attributes["Automatic-Module-Name"] = "org.bikesched.admin.web"
    from(rootProject.layout.projectDirectory) {
        include("LICENSE", "NOTICE")
        into("META-INF")
    }
    from("src/shaded/resources")
}

// Preserve core's API dependency for consumers compiling against the shaded artifact.
val shadedApiElements = configurations.create("shadedApiElements") {
    isCanBeResolved = false
    extendsFrom(configurations.api.get())
    val runtimeElements = configurations.named("shadowRuntimeElements").get()
    runtimeElements.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val attribute = key as Attribute<Any>
        attributes.attribute(attribute, runtimeElements.attributes.getAttribute(attribute)!!)
    }
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
    outgoing.artifact(tasks.shadowJar)
}
(components["shadow"] as AdhocComponentWithVariants).addVariantsFromConfiguration(shadedApiElements) {
    mapToMavenScope("compile")
}

publishing.publications.create<MavenPublication>("shaded") {
    artifactId = "bikesched-admin-shaded"
    from(components["shadow"])
    artifact(tasks.named("sourcesJar"))
    artifact(tasks.named("javadocJar"))
    pom {
        name = "bikesched-admin-shaded"
        description = "Admin UI and JSON API for bikesched with relocated nanojson"
        url = "https://github.com/bikesched/bikesched"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        scm {
            url = "https://github.com/bikesched/bikesched"
            connection = "scm:git:https://github.com/bikesched/bikesched.git"
        }
    }
}

// Exercise the actual shaded jar while the tests also use unrelocated nanojson to read responses.
val shadedTest = tasks.register<Test>("shadedTest") {
    description = "Runs admin tests against the shaded jar, alongside regular nanojson"
    group = "verification"
    dependsOn(tasks.shadowJar)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile }) +
        (sourceSets.test.get().runtimeClasspath - sourceSets.main.get().output)
    shouldRunAfter(tasks.test)
}
tasks.check {
    dependsOn(shadedTest)
}
