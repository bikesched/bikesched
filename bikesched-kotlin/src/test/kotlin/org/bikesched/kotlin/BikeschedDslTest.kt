// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.bikesched.event.listener.EventListener
import org.bikesched.event.listener.EventPublication
import org.bikesched.job.JobState
import org.bikesched.job.RecurrenceParser
import org.bikesched.job.listener.JobListener
import org.junit.jupiter.api.Test

class BikeschedDslTest {

  @Test
  fun `builds an instance from the DSL`() {
    val published = mutableListOf<EventPublication>()
    bikesched(TestDatabase.dataSource) {
        nodeName = "dsl-node"
        maxConcurrentJobs = 3
        autoStartPolling = false
        listenForDueJobs = true
        notifyInterval = 200.milliseconds
        defaultJobTimeout = 5.minutes
        defaultEventTimeout = 1.minutes
        retryBackoff = exponentialBackoff(1.seconds, 1.minutes)
        failureHandlerAttempts = 5
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
        eventListener =
          object : EventListener {
            override fun onEventPublished(publication: EventPublication) {
              published += publication
            }
          }
        recurrenceParsers = listOf(RecurrenceParser.isoDuration())
      }
      .use { bikesched ->
        assertThat(bikesched.node().name()).isEqualTo("dsl-node")
        assertThat(bikesched.node().isPolling).isFalse()
        assertThat(bikesched.node().id()).`as`("no id before polling").isNull()

        val id = bikesched.start().id()
        assertThat(id).isNotNull()

        val jobId = bikesched.createJob(name = unique("dsl-history"), delay = 1.hours) {}
        assertThat(bikesched.findJobHistory(jobId).map { it.state() }).containsExactly("SCHEDULED")

        val recurring = unique("dsl-recurring")
        bikesched.newJob(recurring).handler { _ -> }.schedule("PT1H")
        assertThat(bikesched.removeRecurringJob(recurring)).isTrue()

        val eventId = bikesched.newEvent(unique("dsl-event"), "k").publish()
        assertThat(published.map { it.eventId() }).containsExactly(eventId)
      }
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
