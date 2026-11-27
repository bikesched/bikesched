// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bikesched.job.JobState
import org.bikesched.job.Poll
import org.junit.jupiter.api.Test

class SuspendingHandlersTest {

  data class Order(val id: String, val items: List<String>)

  /** A suspending service call, as a Ktor client or a coroutine-based repository makes it. */
  private suspend fun price(item: String): Int =
    withContext(Dispatchers.Default) {
      delay(10.milliseconds)
      item.length
    }

  @Test
  fun `handlers and steps call suspending functions and still suspend at event waits`() {
    val orders = unique("suspend-order")
    val paid = unique("suspend-paid")
    val totals = CopyOnWriteArrayList<Int>()
    val threads = CopyOnWriteArrayList<Boolean>()

    testNode("suspend-handlers").use { bikesched ->
      bikesched.handleJobs<Order>(orders) { order ->
        delay(10.milliseconds)
        threads += Thread.currentThread().isVirtual
        val total =
          step<Int>("price") {
            coroutineScope { order.items.map { async { price(it) } }.awaitAll().sum() }
          }
        val ready =
          poll<Boolean>("ready", interval = 50.milliseconds) { step ->
            delay(5.milliseconds)
            if (step.attempts() < 2) Poll.notReady() else Poll.ready(true)
          }
        val payment = waitForEvent<Int>(step = "await-payment", name = paid, key = order.id)
        check(ready && payment == total)
        totals += total
      }
      val jobId = bikesched.createJob(name = orders, payload = Order("o-1", listOf("bike", "bell")))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      bikesched.publishEvent(name = paid, key = "o-1", payload = 8)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(totals).containsExactly(8)
      // The handler stays on the job's virtual thread on every run (poll checks and the event wait
      // replay it).
      assertThat(threads).isNotEmpty().containsOnly(true)
    }
  }

  @Test
  fun `the job's timeout cancels a suspended handler`() {
    val name = unique("suspend-timeout")
    val cancelled = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String?>()

    testNode("suspend-timeout").use { bikesched ->
      val jobId =
        bikesched.createJob(
          name = name,
          timeout = 200.milliseconds,
          maxAttempts = 1,
          onFailure = { errors += error() },
        ) {
          try {
            delay(1.minutes)
          } catch (e: CancellationException) {
            cancelled += "delay cancelled"
            throw e
          }
        }

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(cancelled).containsExactly("delay cancelled")
      assertThat(errors).singleElement().asString().contains("Job timed out after PT0.2S")
    }
  }

  @Test
  fun `compensations and failure handlers suspend too`() {
    val name = unique("suspend-compensate")
    val handled = CopyOnWriteArrayList<String>()

    testNode("suspend-compensate").use { bikesched ->
      bikesched.handleJobFailures<Order>(name) { order ->
        delay(10.milliseconds)
        handled += "failed ${order.id}"
      }
      bikesched.handleJobs<Order>(name) { order ->
        step<String>(
          "reserve",
          compensation = { _, reservation ->
            delay(10.milliseconds)
            handled += "released $reservation"
          },
        ) {
          delay(10.milliseconds)
          "r-${order.id}"
        }
        step<Unit>("charge", onFailure = { delay(10.milliseconds) }) {
          delay(10.milliseconds)
          error("card declined")
        }
      }
      val jobId =
        bikesched.createJob(name = name, payload = Order("o-2", listOf("bike")), maxAttempts = 1)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(handled).containsExactlyInAnyOrder("released r-o-2", "failed o-2")
    }
  }

  @Test
  fun `a timeout during a step also cancels the handler catching the step's exception`() {
    val name = unique("suspend-step-timeout")
    val outcomes = CopyOnWriteArrayList<String>()

    testNode("suspend-step-timeout").use { bikesched ->
      val jobId =
        bikesched.createJob(name = name, timeout = 200.milliseconds, maxAttempts = 1) {
          runCatching { step<Unit>("call") { delay(1.minutes) } }
            .onFailure { outcomes += "step failed" }
          try {
            delay(1.minutes)
            outcomes += "handler continued"
          } catch (e: CancellationException) {
            outcomes += "handler cancelled"
            throw e
          }
        }

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(outcomes).containsExactly("step failed", "handler cancelled")
    }
  }

  @Test
  fun `an event wait suspends the job from another dispatcher and from an async child`() {
    val name = unique("suspend-wait-context")
    val event = unique("suspend-wait-event")
    val received = CopyOnWriteArrayList<Int>()

    testNode("suspend-wait-context").use { bikesched ->
      bikesched.handleJobs(name) {
        received += withContext(Dispatchers.IO) { waitForEvent<Int>("io", event, "io") }
        received += coroutineScope { async { waitForEvent<Int>("async", event, "async") }.await() }
      }
      val jobId = bikesched.createJob(name = name)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      bikesched.publishEvent(name = event, key = "io", payload = 1)
      await().untilAsserted { assertThat(received).containsExactly(1) }
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      bikesched.publishEvent(name = event, key = "async", payload = 2)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      // The first wait's payload is received again when the job replays for the second event.
      assertThat(received).containsExactly(1, 1, 2)
    }
  }

  @Test
  fun `steps launched in parallel fail the attempt`() {
    val name = unique("suspend-parallel")
    val errors = CopyOnWriteArrayList<String?>()

    testNode("suspend-parallel").use { bikesched ->
      val jobId =
        bikesched.createJob(name = name, maxAttempts = 1, onFailure = { errors += error() }) {
          coroutineScope {
            launch { step<Unit>("a") { delay(100.milliseconds) } }
            launch { step<Unit>("b") { delay(100.milliseconds) } }
          }
        }

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(errors)
        .singleElement()
        .asString()
        .contains("IllegalStateException")
        .contains("step 'a'")
    }
  }
}
