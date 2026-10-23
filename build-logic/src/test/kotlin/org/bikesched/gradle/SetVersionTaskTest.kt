package org.bikesched.gradle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SetVersionTaskTest {
    private val token = SetVersionTask.versionToken("0.1.0")

    private fun replace(text: String): String = token.replace(text, "0.1.1")

    @Test
    fun `replaces the version in dependency snippets and sentences`() {
        assertThat(replace("""implementation("org.bikesched:bikesched-core:0.1.0")"""))
            .isEqualTo("""implementation("org.bikesched:bikesched-core:0.1.1")""")
        assertThat(replace("<version>0.1.0</version>")).isEqualTo("<version>0.1.1</version>")
        assertThat(replace("These docs are for version `0.1.0`."))
            .isEqualTo("These docs are for version `0.1.1`.")
        assertThat(replace("Released as 0.1.0.")).isEqualTo("Released as 0.1.1.")
    }

    @Test
    fun `leaves other versions containing it alone`() {
        for (text in listOf("10.1.0", "0.1.0.1", "0.1.0-SNAPSHOT", "v0.1.0", "x0.1.0", "0.1.01")) {
            assertThat(replace(text)).isEqualTo(text)
        }
    }

    @Test
    fun `replaces a snapshot version as a whole`() {
        val snapshot = SetVersionTask.versionToken("0.2.0-SNAPSHOT")
        assertThat(snapshot.replace("bikesched-core:0.2.0-SNAPSHOT\"", "0.2.0"))
            .isEqualTo("bikesched-core:0.2.0\"")
        assertThat(snapshot.replace("0.2.0-SNAPSHOT-2", "0.2.0")).isEqualTo("0.2.0-SNAPSHOT-2")
    }
}
