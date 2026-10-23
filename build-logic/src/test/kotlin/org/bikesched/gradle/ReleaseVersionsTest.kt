package org.bikesched.gradle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReleaseVersionsTest {
    @Test
    fun `a minor release drops the snapshot and starts a release branch`() {
        assertThat(ReleaseVersions.of("0.2.0-SNAPSHOT", ReleaseType.MINOR))
            .isEqualTo(
                ReleaseVersions(
                    current = "0.2.0-SNAPSHOT",
                    version = "0.2.0",
                    nextSnapshot = "0.3.0-SNAPSHOT",
                    releaseBranch = "release/0.2.x",
                    releaseBranchSnapshot = "0.2.1-SNAPSHOT",
                )
            )
    }

    @Test
    fun `a major release is the next major`() {
        assertThat(ReleaseVersions.of("0.2.0-SNAPSHOT", ReleaseType.MAJOR))
            .isEqualTo(
                ReleaseVersions(
                    current = "0.2.0-SNAPSHOT",
                    version = "1.0.0",
                    nextSnapshot = "1.1.0-SNAPSHOT",
                    releaseBranch = "release/1.0.x",
                    releaseBranchSnapshot = "1.0.1-SNAPSHOT",
                )
            )
    }

    @Test
    fun `a patch release continues its release branch`() {
        assertThat(ReleaseVersions.of("0.1.3-SNAPSHOT", ReleaseType.PATCH))
            .isEqualTo(
                ReleaseVersions(
                    current = "0.1.3-SNAPSHOT",
                    version = "0.1.3",
                    nextSnapshot = "0.1.4-SNAPSHOT",
                    releaseBranch = null,
                    releaseBranchSnapshot = null,
                )
            )
    }

    @Test
    fun `a minor release needs patch version 0`() {
        assertThatThrownBy { ReleaseVersions.of("0.1.3-SNAPSHOT", ReleaseType.MINOR) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("release branch")
    }

    @Test
    fun `a patch release needs a patch version above 0`() {
        assertThatThrownBy { ReleaseVersions.of("0.2.0-SNAPSHOT", ReleaseType.PATCH) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("released from main")
    }

    @Test
    fun `only snapshots are released`() {
        for (version in listOf("0.2.0", "0.2.0-RC1", "0.2.0-SNAPSHOT+build.1")) {
            assertThatThrownBy { ReleaseVersions.of(version, ReleaseType.MINOR) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `release types are parsed ignoring case`() {
        assertThat(ReleaseType.parse("Minor")).isEqualTo(ReleaseType.MINOR)
        assertThatThrownBy { ReleaseType.parse("hotfix") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the properties are GitHub output lines`() {
        assertThat(ReleaseVersions.of("0.1.3-SNAPSHOT", ReleaseType.PATCH).toProperties())
            .isEqualTo(
                """
                current=0.1.3-SNAPSHOT
                version=0.1.3
                nextSnapshot=0.1.4-SNAPSHOT
                releaseBranch=
                releaseBranchSnapshot=

                """
                    .trimIndent()
            )
    }
}
