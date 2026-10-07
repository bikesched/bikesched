// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bikesched.job.JobResult
import org.bikesched.job.JobState
import org.junit.jupiter.api.Test

class ChildJobsTest {

  data class Order(val id: String, val amount: Int)

  data class Receipt(val orderId: String, val charged: Int)

  @Test
  fun `a parent starts a child and awaits its result`() {
    val parent = unique("fulfil")
    val charge = unique("charge")
    val receipts = CopyOnWriteArrayList<JobResult<Receipt>>()
    val compensated = CopyOnWriteArrayList<UUID>()

    testNode("child-jobs").use { bikesched ->
      bikesched.handleJobs<Order>(charge) { order -> complete(Receipt(order.id, order.amount)) }
      bikesched.handleJobs<Order>(parent) { order ->
        val child =
          startJob(
            type = charge,
            payload = order,
            stepName = "charge",
            compensation = { _, id -> compensated += id },
          )
        val receipt = awaitJob<Receipt>(child)
        receipts += receipt
        complete(receipt.orElseThrow())
      }
      val jobId = bikesched.createJob(type = parent, payload = Order("o-1", 100))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      val childId = receipts.single().jobId()
      assertThat(receipts).containsExactly(JobResult.Succeeded(childId, Receipt("o-1", 100)))
      assertThat(bikesched.findJobOrNull(jobId)?.result(Receipt::class.java))
        .contains(Receipt("o-1", 100))
      assertThat(bikesched.findJobOrNull(childId)?.parentJobId()).contains(jobId)
      assertThat(compensated).isEmpty()
    }
  }

  @Test
  fun `awaiting with a timeout returns TimedOut`() {
    val parent = unique("impatient")
    val slow = unique("slow")
    val outcomes = CopyOnWriteArrayList<JobResult<String?>>()

    testNode("child-jobs-timeout").use { bikesched ->
      bikesched.handleJobs(slow) {}
      bikesched.handleJobs(parent) {
        val child = startJob(type = slow, delay = 1.hours)
        outcomes += awaitJob<String?>(child, timeout = 200.milliseconds)
      }
      val jobId = bikesched.createJob(type = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(outcomes).hasSize(1)
      assertThat(outcomes.single()).isInstanceOf(JobResult.TimedOut::class.java)
    }
  }
}
