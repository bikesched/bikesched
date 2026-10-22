package org.bikesched.gradle

import java.time.LocalDate
import java.time.ZoneOffset
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Adds the changelog section JReleaser generated for a release to CHANGELOG.md, newest first:
 * below the [ANCHOR] line, under a heading with the version and the date.
 */
@DisableCachingByDefault(because = "Edits a source file in place")
abstract class PrependChangelogTask : DefaultTask() {
    @get:Input abstract val version: Property<String>

    /** The section JReleaser generated (build/jreleaser/release/CHANGELOG.md). */
    @get:Internal abstract val section: RegularFileProperty

    @get:Internal abstract val changelog: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepend() {
        val sectionFile = section.get().asFile
        if (!sectionFile.isFile) {
            throw GradleException("No generated changelog at $sectionFile, run jreleaserChangelog")
        }
        val file = changelog.get().asFile
        val text = file.readText()
        val anchor = text.indexOf(ANCHOR)
        if (anchor < 0) throw GradleException("$file has no line $ANCHOR")
        val heading = "## ${version.get()} (${LocalDate.now(ZoneOffset.UTC)})"
        if (text.contains("\n## ${version.get()} (")) {
            throw GradleException("$file already has ${version.get()}")
        }
        val insertAt = anchor + ANCHOR.length
        val entry = "\n\n$heading\n\n${sectionFile.readText().trim()}"
        file.writeText(text.substring(0, insertAt) + entry + text.substring(insertAt))
        logger.lifecycle("Added ${version.get()} to $file")
    }

    companion object {
        const val ANCHOR = "<!-- Releases are added below this line by the release workflow. -->"
    }
}
