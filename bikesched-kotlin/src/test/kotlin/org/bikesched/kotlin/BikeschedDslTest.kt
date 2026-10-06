package org.bikesched.kotlin

import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bikesched.JobListener
import org.bikesched.job.JobState
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
      eventCleanUpInterval = 10.minutes
      nodeCleanUpInterval = 30.seconds
      jobHistoryRetention = 1.hours
      jobHistoryCleanUpInterval = 10.minutes
      jobRetention = 30.days
      jobCleanUpInterval = 10.minutes
      statementTimeout = 30.seconds
      hardTimeoutGrace = 30.seconds
      jobListener = object : JobListener {}
    }
      .use { bikesched ->
        assertThat(bikesched.node().name()).isEqualTo("dsl-node")
        assertThat(bikesched.node().isPolling).isFalse()
        assertThat(bikesched.node().idOrNull).`as`("no id before polling").isNull()

        val id = bikesched.startPolling().idOrNull
        assertThat(id).isNotNull()

        val jobId = bikesched.createJob(type = unique("dsl-history"), delay = 1.hours) {}
        assertThat(bikesched.findJobHistory(jobId).map { it.state() }).containsExactly("SCHEDULED")
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
