package org.bikesched.gradle

import com.github.zafarkhaja.semver.Version

/** The kind of release the release workflow cuts (RELEASING.md). */
enum class ReleaseType {
    MAJOR,
    MINOR,
    PATCH;

    companion object {
        fun parse(value: String): ReleaseType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown release type '$value', expected major, minor or patch"
                )
    }
}

/**
 * The versions of one release, derived from the snapshot version of the branch it is cut from.
 *
 * @property current the branch's snapshot version the release is derived from
 * @property version the version to release
 * @property nextSnapshot the snapshot the branch the release was cut from continues with
 * @property releaseBranch the maintenance branch a major or minor release creates, `null` for a
 *   patch (it is cut from that branch)
 * @property releaseBranchSnapshot the snapshot that maintenance branch starts with
 */
data class ReleaseVersions(
    val current: String,
    val version: String,
    val nextSnapshot: String,
    val releaseBranch: String?,
    val releaseBranchSnapshot: String?,
) {
    /** `key=value` lines, the format of `$GITHUB_OUTPUT`. */
    fun toProperties(): String =
        listOf(
                "current" to current,
                "version" to version,
                "nextSnapshot" to nextSnapshot,
                "releaseBranch" to releaseBranch.orEmpty(),
                "releaseBranchSnapshot" to releaseBranchSnapshot.orEmpty(),
            )
            .joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }

    companion object {
        private const val SNAPSHOT = "SNAPSHOT"

        /**
         * Derives the release from [current], the branch's version: always the next version as a
         * snapshot (`0.2.0-SNAPSHOT` on `main`, `0.1.3-SNAPSHOT` on `release/0.1.x`). Minor and
         * patch releases drop the `-SNAPSHOT`, a major release is the next major (`1.0.0` from
         * `0.2.0-SNAPSHOT`).
         */
        fun of(current: String, type: ReleaseType): ReleaseVersions {
            val snapshot = Version.parse(current)
            require(snapshot.preReleaseVersion().orElse(null) == SNAPSHOT) {
                "Version $current is not a snapshot (X.Y.Z-SNAPSHOT)"
            }
            require(snapshot.buildMetadata().isEmpty) { "Version $current has build metadata" }
            val release =
                when (type) {
                    ReleaseType.MAJOR -> snapshot.nextMajorVersion()
                    ReleaseType.MINOR -> {
                        require(snapshot.patchVersion() == 0L) {
                            "A minor release needs a X.Y.0-SNAPSHOT version, not $current: " +
                                "patches are released from their release branch"
                        }
                        snapshot.toStableVersion()
                    }
                    ReleaseType.PATCH -> {
                        require(snapshot.patchVersion() > 0L) {
                            "A patch release needs a X.Y.Z-SNAPSHOT version with Z > 0, not " +
                                "$current: major and minor versions are released from main"
                        }
                        snapshot.toStableVersion()
                    }
                }
            return when (type) {
                ReleaseType.PATCH ->
                    ReleaseVersions(
                        current = current,
                        version = release.toString(),
                        nextSnapshot = release.nextPatchVersion(SNAPSHOT).toString(),
                        releaseBranch = null,
                        releaseBranchSnapshot = null,
                    )
                else ->
                    ReleaseVersions(
                        current = current,
                        version = release.toString(),
                        nextSnapshot = release.nextMinorVersion(SNAPSHOT).toString(),
                        releaseBranch =
                            "release/${release.majorVersion()}.${release.minorVersion()}.x",
                        releaseBranchSnapshot = release.nextPatchVersion(SNAPSHOT).toString(),
                    )
            }
        }
    }
}
