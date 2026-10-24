// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bikesched.job.Job
import org.bikesched.job.JobState
import org.bikesched.job.Step
import org.junit.jupiter.api.Test

class TypedJobTest {

  data class SendMail(val to: String, val subject: String)

  data class Receipt(val messageId: String, val recipients: List<String>)

  @Test
  fun `typed handler receives the mapped payload with the job as receiver`() {
    val sendMail = unique("send-mail")
    val received = CopyOnWriteArrayList<Pair<Job, SendMail>>()

    testNode("typed").use { bikesched ->
      bikesched.handleJobs<SendMail>(sendMail) { mail -> received += this to mail }
      val jobId =
        bikesched.createJob(name = sendMail, payload = SendMail("a@b.de", "Hi"), priority = 5)

      await().untilAsserted { assertThat(received).hasSize(1) }
      val (job, mail) = received.single()
      assertThat(job.id()).isEqualTo(jobId)
      assertThat(mail).isEqualTo(SendMail("a@b.de", "Hi"))
      assertThat(job.payload<SendMail>()).isEqualTo(mail)
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
    }
  }

  @Test
  fun `typed job without payload fails`() {
    val sendMail = unique("no-payload")
    var called = false

    testNode("no-payload").use { bikesched ->
      bikesched.handleJobs<SendMail>(sendMail) { called = true }
      val jobId = bikesched.createJob(name = sendMail, maxAttempts = 1)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(called).`as`("the handler is not called without payload").isFalse()
    }
  }

  @Test
  fun `handler with nullable payload type accepts jobs without payload`() {
    val type = unique("optional-payload")
    val received = CopyOnWriteArrayList<String>()

    testNode("optional-payload").use { bikesched ->
      bikesched.handleJobs<SendMail?>(type) { mail -> received += mail?.to ?: "none" }
      bikesched.createJob(name = type)
      bikesched.createJob(name = type, payload = SendMail("a@b.de", "Hi"))

      await().untilAsserted { assertThat(received).containsExactlyInAnyOrder("none", "a@b.de") }
    }
  }

  @Test
  fun `delays and timeouts accept kotlin durations`() {
    val later = unique("later")

    testNode("durations").use { bikesched ->
      bikesched.handleJobs<SendMail>(later) {}
      val delayed =
        bikesched
          .newJob(later)
          .payload(SendMail("x@y.de", "Later"))
          .timeout(5.minutes)
          .schedule(1.hours)
      assertThat(bikesched.findJobOrNull(delayed)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThat(bikesched.cancel(delayed)).isTrue()
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(delayed)?.state()).isEqualTo(JobState.CANCELLED)
      }
    }
  }

  @Test
  fun `steps store typed results and run only once`() {
    val order = unique("steps")
    val sends = AtomicInteger()
    val confirmations = AtomicInteger()
    val receipts = CopyOnWriteArrayList<Receipt>()

    testNode("steps").use { bikesched ->
      bikesched.handleJobs<SendMail>(order) { mail ->
        val receipt =
          step<Receipt>("send") { Receipt("m-${sends.incrementAndGet()}", listOf(mail.to)) }
        step<Unit>("confirm") { confirmations.incrementAndGet() } // no stored result
        receipts.add(receipt)
        if (attempts() == 1) fail("retry once").retryAfter(10.milliseconds)
      }
      val jobId = bikesched.createJob(name = order, payload = SendMail("a@b.de", "Order"))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(receipts)
        .`as`("the retry got the stored receipt instead of sending again")
        .containsExactly(Receipt("m-1", listOf("a@b.de")), Receipt("m-1", listOf("a@b.de")))
      assertThat(sends).hasValue(1)
      assertThat(confirmations).hasValue(1)
    }
  }

  @Test
  fun `cancelling runs the compensations of typed and unit steps`() {
    val booking = unique("compensate")
    val compensated = CopyOnWriteArrayList<String>()
    val compensatedSteps = CopyOnWriteArrayList<Step>()

    testNode("compensate").use { bikesched ->
      bikesched.handleJobs<SendMail>(booking) { mail ->
        step<Receipt>(
          "reserve",
          compensation = { step, receipt ->
            compensatedSteps += step
            compensated += "release ${receipt.messageId}"
          },
        ) {
          Receipt("r-1", listOf(mail.to))
        }
        step<String>("log") { "no compensation" }
        step<Unit>("notify", compensation = { _, _ -> compensated += "recall notification" }) {}
        if (!isCompensating) cancel("customer cancelled")
      }
      val jobId = bikesched.createJob(name = booking, payload = SendMail("a@b.de", "Booking"))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.CANCELLED)
      }
      assertThat(compensated).containsExactly("recall notification", "release r-1")
      assertThat(compensatedSteps).containsExactly(Step(jobId, "reserve", 1))
    }
  }
}
