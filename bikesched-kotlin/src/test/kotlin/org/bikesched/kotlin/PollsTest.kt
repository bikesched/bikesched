// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.bikesched.job.JobState
import org.bikesched.job.Poll
import org.junit.jupiter.api.Test

class PollsTest {

  data class Report(val id: String, val pages: Int)

  @Test
  fun `a poll returns the ready value and later runs return the stored one`() {
    val type = unique("poll-report")
    val reports = CopyOnWriteArrayList<Report>()
    val checks = CopyOnWriteArrayList<Int>()

    testNode("polls").use { bikesched ->
      bikesched.handleJobs(type) {
        reports +=
          poll<Report>("await-report", interval = 50.milliseconds) { step ->
            checks += step.attempts()
            if (step.attempts() < 3) Poll.notReady() else Poll.ready(Report("r-1", 12))
          }
        sleep("replay", 50.milliseconds)
      }
      val jobId = bikesched.createJob(type = type)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(checks).containsExactly(1, 2, 3)
      assertThat(reports).containsExactly(Report("r-1", 12), Report("r-1", 12))
    }
  }

  @Test
  fun `a poll with a growing interval, a Unit poll and a timeout fallback`() {
    val type = unique("poll-variants")
    val intervals = CopyOnWriteArrayList<Int>()
    val results = CopyOnWriteArrayList<Any?>()

    testNode("polls-variants").use { bikesched ->
      bikesched.handleJobs(type) {
        val unit =
          poll<Unit>(
            "unit",
            interval = { check: Int ->
              intervals += check
              java.time.Duration.ofMillis(50)
            },
          ) { step ->
            if (step.attempts() < 2) Poll.notReady() else Poll.ready(Unit)
          }
        val report =
          poll<Report?>(
            "never-ready",
            interval = 50.milliseconds,
            timeout = 150.milliseconds,
            onTimeout = { null },
          ) {
            Poll.notReady()
          }
        results.addAll(listOf(unit, report))
      }
      val jobId = bikesched.createJob(type = type)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(intervals).containsExactly(1)
      assertThat(results).containsExactly(Unit, null)
    }
  }

  @Test
  fun `onTimeout requires a timeout`() {
    val type = unique("poll-invalid")
    val errors = CopyOnWriteArrayList<Throwable>()

    testNode("polls-invalid").use { bikesched ->
      bikesched.handleJobs(type) {
        errors +=
          runCatching {
            poll<String?>("p", interval = 50.milliseconds, onTimeout = { null }) {
              Poll.ready(null)
            }
          }
            .exceptionOrNull()!!
      }
      val jobId = bikesched.createJob(type = type)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThatThrownBy { throw errors.single() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("onTimeout requires a timeout")
    }
  }
}
