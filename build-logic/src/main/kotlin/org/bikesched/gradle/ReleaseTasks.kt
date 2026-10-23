package org.bikesched.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Prints the versions of the next release as `key=value` lines (see [ReleaseVersions]):
 * `./gradlew -q releaseVersions -PreleaseType=minor`.
 */
@DisableCachingByDefault(because = "Prints to the console")
abstract class ReleaseVersionsTask : DefaultTask() {
    /** The version in the catalog: the snapshot of the current branch. */
    @get:Input abstract val currentVersion: Property<String>

    /** `major`, `minor` or `patch`. */
    @get:Input abstract val releaseType: Property<String>

    init {
        // Always prints: the result depends on the command line, nothing to be up to date with.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun print() {
        val versions =
            try {
                ReleaseVersions.of(currentVersion.get(), ReleaseType.parse(releaseType.get()))
            } catch (e: IllegalArgumentException) {
                throw GradleException(e.message ?: "Invalid release", e)
            }
        print(versions.toProperties())
    }
}

/**
 * Sets the project version in the catalog and, optionally, the version the dependency snippets in
 * the docs show: `./gradlew setVersion -PnewVersion=0.3.0-SNAPSHOT -PdocsFrom=0.2.0-SNAPSHOT
 * -PdocsTo=0.3.0-SNAPSHOT`. Snippets show the version of their branch (RELEASING.md).
 */
@DisableCachingByDefault(because = "Edits source files in place")
abstract class SetVersionTask : DefaultTask() {
    @get:Input @get:Optional abstract val newVersion: Property<String>

    @get:Input @get:Optional abstract val docsFrom: Property<String>

    @get:Input @get:Optional abstract val docsTo: Property<String>

    @get:Internal abstract val catalog: RegularFileProperty

    /** Markdown files whose dependency snippets show the version. */
    @get:Internal abstract val docs: ConfigurableFileCollection

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun update() {
        if (newVersion.isPresent) {
            val file = catalog.get().asFile
            val text = file.readText()
            val updated = CATALOG_VERSION.replace(text) { "${it.groupValues[1]}${newVersion.get()}\"" }
            if (updated == text && !text.contains("bikesched = \"${newVersion.get()}\"")) {
                throw GradleException("No `bikesched = \"…\"` version found in $file")
            }
            file.writeText(updated)
            logger.lifecycle("Version: ${newVersion.get()}")
        }
        if (docsFrom.isPresent != docsTo.isPresent) {
            throw GradleException("docsFrom and docsTo go together")
        }
        if (docsFrom.isPresent && docsFrom.get() != docsTo.get()) {
            val from = versionToken(docsFrom.get())
            docs.files.sorted().forEach { file ->
                val text = file.readText()
                val updated = from.replace(text, docsTo.get())
                if (updated != text) {
                    file.writeText(updated)
                    logger.lifecycle("Docs version ${docsFrom.get()} -> ${docsTo.get()}: $file")
                }
            }
        }
    }

    companion object {
        private val CATALOG_VERSION = Regex("""(?m)^(bikesched = ")[^"]*"""")

        /**
         * Matches [version] as a whole token: not `0.1.0` inside `10.1.0`, `0.1.0.1` or
         * `0.1.0-SNAPSHOT`, but at the end of a sentence.
         */
        fun versionToken(version: String): Regex =
            Regex("""(?<![\w.-])${Regex.escape(version)}(?![\w-]|\.\d)""")
    }
}
