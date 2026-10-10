// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.bikesched.job.JobState
import org.junit.jupiter.api.Test

/**
 * The Kotlin-style entry points: handleJobs, createJob, createRecurringJob, publishEvent and
 * waitForEvent.
 */
class SchedulingTest {

  data class Report(val name: String)

  @Test
  fun `job registers its trailing handler and runs`() {
    val reports = unique("report")
    val cleanups = unique("cleanup")
    val ran = CopyOnWriteArrayList<String>()

    testNode("job").use { bikesched ->
      val reportId =
        bikesched.createJob(
          type = reports,
          payload = Report("sales"),
          priority = 5,
          maxAttempts = 1,
        ) { report ->
          ran += "${report.name} ${attempts()}/${maxAttempts()}"
        }
      val cleanupId =
        bikesched.createJob(type = cleanups, timeout = 1.minutes) { ran += "cleanup ${id()}" }

      await().untilAsserted {
        assertThat(ran).containsExactlyInAnyOrder("sales 1/1", "cleanup $cleanupId")
      }
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(reportId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      // The handler is registered under the type now: further jobs need none.
      bikesched.createJob(type = cleanups)
      await().untilAsserted { assertThat(ran).hasSize(3) }
    }
  }

  @Test
  fun `step failure handler gets the stored error and resolves with a fallback`() {
    val seen = CopyOnWriteArrayList<String>()
    testNode("step-on-failure").use { bikesched ->
      val id =
        bikesched.createJob(type = unique("step-on-failure"), maxAttempts = 1) {
          val report =
            step<Report>(
              "fetch",
              onFailure = { failing ->
                seen += failing.error()
                failing.resolve(Report("fallback"))
              },
            ) {
              error("report service down")
            }
          step<Unit>("notify", onFailure = { failing -> failing.resolve(Unit) }) {
            error("mail down")
          }
          seen += report.name
        }

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(id)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(seen)
        .containsExactly("java.lang.IllegalStateException: report service down", "fallback")
    }
  }

  @Test
  fun `failure handlers get the failed job as receiver and the payload`() {
    val handled = CopyOnWriteArrayList<String>()
    val reports = unique("failing-report")
    val pings = unique("failing-ping")
    val created = unique("failing-created")
    testNode("job-failure").use { bikesched ->
      bikesched.handleJobs<Report>(reports) { error("report service down") }
      bikesched.handleJobFailures<Report>(reports) { report ->
        handled += "${report.name}: ${error()}"
      }
      bikesched.handleJobs(pings) { error("down") }
      bikesched.handleJobFailures(pings) { handled += "ping ${state()}" }
      val ids =
        listOf(
          bikesched.createJob(type = reports, payload = Report("sales"), maxAttempts = 1),
          bikesched.createJob(type = pings, maxAttempts = 1),
          bikesched.createJob(
            type = created,
            payload = Report("stock"),
            maxAttempts = 1,
            onFailure = { report -> handled += "created ${report.name}" },
          ) {
            error("boom")
          },
        )

      ids.forEach { id ->
        await().untilAsserted {
          assertThat(bikesched.findJobOrNull(id)?.state()).isEqualTo(JobState.FAILED)
        }
      }
      assertThat(handled)
        .containsExactlyInAnyOrder(
          "sales: java.lang.IllegalStateException: report service down",
          "ping FAILED",
          "created stock",
        )
    }
  }

  @Test
  fun `sleep takes a kotlin Duration`() {
    val ran = CopyOnWriteArrayList<String>()
    testNode("sleep").use { bikesched ->
      val id =
        bikesched.createJob(type = unique("sleep")) {
          ran += "before"
          sleep("nap", 200.milliseconds)
          ran += "after"
        }
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(id)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(ran).containsExactly("before", "before", "after")
    }
  }

  @Test
  fun `handleJobs without payload gets the job as receiver`() {
    val type = unique("no-payload-handler")
    val ids = CopyOnWriteArrayList<UUID>()

    testNode("handle").use { bikesched ->
      bikesched.handleJobs(type) { ids += id() }
      val jobId = bikesched.createJob(type = type)

      await().untilAsserted { assertThat(ids).containsExactly(jobId) }
    }
  }

  @Test
  fun `job is due at the given instant or after the delay`() {
    val type = unique("due")

    testNode("due").use { bikesched ->
      bikesched.handleJobs(type) {}
      val at = Instant.now().plusSeconds(3600)
      val atId = bikesched.createJob(type = type, at = at)
      val delayedId = bikesched.createJob(type = type, delay = 1.hours)

      assertThat(bikesched.findJobOrNull(atId)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThat(bikesched.findJobOrNull(delayedId)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThatThrownBy { bikesched.createJob(type = type, at = at, delay = 1.hours) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("at and delay are mutually exclusive")
      listOf(atId, delayedId).forEach { bikesched.cancel(it) }
    }
  }

  @Test
  fun `job joins the caller's transaction`() {
    val type = unique("transactional")

    testNode("transactional").use { bikesched ->
      bikesched.handleJobs(type) {}
      val rolledBack =
        TestDatabase.dataSource.connection.use { connection ->
          connection.autoCommit = false
          val id = bikesched.createJob(type = type, connection = connection, delay = 1.hours)
          connection.rollback()
          id
        }
      val committed =
        TestDatabase.dataSource.connection.use { connection ->
          connection.autoCommit = false
          val id = bikesched.createJob(type = type, connection = connection, delay = 1.hours)
          connection.commit()
          id
        }

      assertThat(bikesched.findJobOrNull(rolledBack)).isNull()
      assertThat(bikesched.findJobOrNull(committed)?.state()).isEqualTo(JobState.SCHEDULED)
      bikesched.cancel(committed)
    }
  }

  @Test
  fun `createRecurringJob registers the recurrence with an optional payload and handler`() {
    val typed = unique("recurring-report")
    val plain = unique("recurring-cleanup")

    testNode("recurring-job").use { bikesched ->
      val reportOccurrence =
        bikesched.createRecurringJob(
          type = typed,
          recurrence = every(1.hours),
          payload = Report("daily"),
          delay = 1.hours,
        ) { report ->
          println(report)
        }
      val firstRun = Instant.now().plusSeconds(7200)
      val cleanupOccurrence =
        bikesched.createRecurringJob(
          type = plain,
          recurrence = every(1.hours),
          firstRun = firstRun,
        ) {}

      assertThat(bikesched.findJobOrNull(reportOccurrence)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThat(bikesched.findJobOrNull(cleanupOccurrence)?.state()).isEqualTo(JobState.SCHEDULED)
      assertThat(
          bikesched.createRecurringJob(
            type = plain,
            recurrence = every(1.hours),
            firstRun = firstRun,
          )
        )
        .`as`("registering again keeps the pending occurrence")
        .isEqualTo(cleanupOccurrence)
      assertThat(bikesched.removeRecurringJob(typed)).isTrue()
      assertThat(bikesched.removeRecurringJob(plain)).isTrue()
    }
  }

  @Test
  fun `waitForEvent returns the payload or the timeout fallback`() {
    val type = unique("wait-for-event")
    val eventName = unique("answer")
    val results = CopyOnWriteArrayList<String>()

    testNode("wait-for-event").use { bikesched ->
      bikesched.handleJobs<Report>(type) { report ->
        val answer =
          waitForEvent<Report>(step = "await-answer", name = eventName, key = report.name)
        val late: Report? =
          waitForEvent(
            step = "await-late",
            name = eventName,
            key = "never",
            timeout = 100.milliseconds,
          ) {
            null
          }
        results += "$answer / $late"
      }
      val jobId = bikesched.createJob(type = type, payload = Report("r-1"))
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      bikesched.publishEvent(name = eventName, key = "r-1", payload = Report("yes"))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(results.last()).isEqualTo("Report(name=yes) / null")
    }
  }

  @Test
  fun `waitForEvent without fallback fails the job once the timeout passes`() {
    val type = unique("wait-timeout")
    val never = unique("never")
    val seenAttempts = CopyOnWriteArrayList<Int>()
    val errors = CopyOnWriteArrayList<String>()

    testNode("wait-timeout").use { bikesched ->
      bikesched.handleJobs(type) {
        seenAttempts += attempts()
        try {
          waitForEvent<Report?>(step = "missing", name = "nothing", key = "k", onTimeout = { null })
        } catch (e: IllegalArgumentException) {
          errors += e.message.orEmpty()
        }
        waitForEvent<Report>(step = "await", name = never, key = "k", timeout = 100.milliseconds)

        step<TypedEventTest.Payment>(name = "") {
          TypedEventTest.Payment("", 100)
        }
      }
      val jobId = bikesched.createJob(type = type)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(errors).contains("onTimeout requires a timeout")
      assertThat(seenAttempts.distinct()).`as`("no retries after the timeout").containsExactly(1)
    }
  }

  @Test
  fun `event publishes with optional payload and connection`() {
    val type = unique("await-events")
    val eventName = unique("event")
    val received = CopyOnWriteArrayList<Any?>()

    testNode("event").use { bikesched ->
      bikesched.handleJobs(type) {
        received += waitForEvent("with-payload", eventName, "a").receive<Report>()
        publishEvent(name = "${eventName}-published", key = "event", payload = Report("shipped"))
        received += waitForEvent("without-payload", eventName, "b").receive<Report?>()
      }
      val jobId = bikesched.createJob(type = type)
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }

      bikesched.publishEvent(name = eventName, key = "a", payload = Report("paid"))
      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.WAITING)
      }
      TestDatabase.dataSource.connection.use { connection ->
        connection.autoCommit = false
        bikesched.publishEvent(name = eventName, key = "b", connection = connection)
        connection.commit()
      }

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(received.takeLast(2)).containsExactly(Report("paid"), null)
    }
  }
}
