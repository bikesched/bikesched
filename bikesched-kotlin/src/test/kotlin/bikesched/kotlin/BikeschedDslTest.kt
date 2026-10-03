package bikesched.kotlin

import bikesched.core.job.JobState
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BikeschedDslTest {

  @Test
  fun `builds an instance from the DSL`() {
    bikesched {
      dataSource = TestDatabase.dataSource
      nodeName = "dsl-node"
      maxConcurrentJobs = 3
      autoStartPolling = false
      defaultJobTimeout = 5.minutes
      defaultEventTimeout = 1.minutes
      retryBackoff = exponentialBackoff(1.seconds, 1.minutes)
      eventRetention = 1.minutes
    }
      .use { bikesched ->
        assertThat(bikesched.node().name()).isEqualTo("dsl-node")
        assertThat(bikesched.node().isPolling).isFalse()
        assertThat(bikesched.node().idOrNull).`as`("no id before polling").isNull()

        val id = bikesched.startPolling().idOrNull
        assertThat(id).isNotNull()
      }
  }

  @Test
  fun `requires a data source`() {
    assertThatThrownBy { bikesched { nodeName = "no-data-source" } }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("dataSource is required")
  }

  @Test
  fun `findJobOrNull returns null for unknown jobs`() {
    testNode("find").use { bikesched ->
      assertThat(bikesched.findJobOrNull(UUID.randomUUID())).isNull()

      val type = unique("find")
      val jobId = bikesched.newJob(type).handler { _ -> }.schedule(1.hours)
      assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SCHEDULED)
    }
  }
}
