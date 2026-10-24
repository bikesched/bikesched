// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bikesched.job.JobState
import org.junit.jupiter.api.Test

class TypedEventTest {

  data class Order(val id: String, val amount: Int)

  data class Payment(val orderId: String, val amount: Int)

  @Test
  fun `typed event wait receives the published payload`() {
    val order = unique("order")
    val paymentReceived = unique("payment-received")
    val payments = CopyOnWriteArrayList<Payment>()

    testNode("typed-event").use { bikesched ->
      bikesched.handleJobs<Order>(order) { order ->
        payments += waitForEvent<Payment>("await-payment", paymentReceived, order.id)
      }
      val jobId = bikesched.createJob(name = order, payload = Order("o-1", 100))
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }

      bikesched.publishEvent(name = paymentReceived, key = "o-1", payload = Payment("o-1", 100))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(payments).containsExactly(Payment("o-1", 100))
    }
  }

  @Test
  fun `timed out waits return the fallback`() {
    val order = unique("order-timeout")
    val paymentReceived = unique("payment-timeout")
    val results = CopyOnWriteArrayList<String>()

    testNode("event-timeout").use { bikesched ->
      bikesched.handleJobs<Order>(order) { o ->
        val payment =
          waitForEvent<Payment?>(
            "await",
            paymentReceived,
            o.id,
            100.milliseconds,
            onFailure = { it.resolve(null) },
          )
        val fallback =
          waitForEvent<Payment>(
            "await-fallback",
            paymentReceived,
            o.id,
            100.milliseconds,
            onFailure = { it.resolve(Payment(o.id, 0)) },
          )
        results += "$payment / $fallback"
      }
      val jobId = bikesched.createJob(name = order, payload = Order("o-2", 50))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(results.last()).isEqualTo("null / Payment(orderId=o-2, amount=0)")
    }
  }

  @Test
  fun `untyped helpers map payloads with reified types`() {
    val type = unique("untyped")
    val eventName = unique("untyped-event")
    val results = CopyOnWriteArrayList<Any?>()

    testNode("untyped").use { bikesched ->
      bikesched.registerJobHandler(type) { job ->
        results += job.payload<Order>()
        results += job.waitForEvent<Payment>("wait", eventName, "k")
        results +=
          job.waitForEvent<Payment?>(
            "wait-timeout",
            eventName,
            "other",
            1.milliseconds,
            onFailure = { it.resolve(null) },
          )
      }
      val jobId = bikesched.newJob(type).payload(Order("o-3", 7)).schedule()
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      bikesched.newEvent(eventName, "k").payload(Payment("o-3", 7)).publish()

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(results.takeLast(3)).containsExactly(Order("o-3", 7), Payment("o-3", 7), null)
    }
  }

  @Test
  fun `recurring jobs accept a kotlin recurrence`() {
    val tick = unique("tick")

    testNode("recurring").use { bikesched ->
      bikesched.handleJobs<Order>(tick) {}
      val first = bikesched.newJob(tick).payload(Order("tick", 0)).schedule(every(1.hours), 1.hours)
      assertThat(bikesched.findJobOrNull(first)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThat(bikesched.removeRecurringJob(tick)).isTrue()
    }
  }
}
