// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bikesched.job.JobBatchResult
import org.bikesched.job.JobFailedException
import org.bikesched.job.JobResult
import org.bikesched.job.JobState
import org.junit.jupiter.api.Test

class BatchesTest {

  data class Render(val imageId: String, val format: String)

  data class Image(val format: String, val bytes: Int)

  data class Scan(val fileId: String)

  data class ScanReport(val scanner: String, val clean: Boolean)

  @Test
  fun `a batch started with the lambda returns typed results in item order`() {
    val parent = unique("render-all")
    val render = unique("render-format")
    val images = CopyOnWriteArrayList<List<Image?>>()
    val keys = CopyOnWriteArrayList<String?>()

    testNode("batches").use { bikesched ->
      bikesched.handleResultJobs<Render, Image>(render) { r ->
        keys += batchKey()
        Image(r.format, r.format.length)
      }
      bikesched.handleJobs<String>(parent) { imageId ->
        val renders =
          startJobBatch<Render, Image>("render", render, parallelism = 2) {
            for (format in listOf("webp", "avif", "png")) item(
              Render(imageId, format),
              key = format,
            )
          }
        images += renders.await()
      }
      val jobId = bikesched.createJob(name = parent, payload = "img-1")

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(images.single())
        .containsExactly(Image("webp", 4), Image("avif", 4), Image("png", 3))
      assertThat(keys).containsExactlyInAnyOrder("webp", "avif", "png")
    }
  }

  @Test
  fun `items, pairs and payloads combine in that order`() {
    val parent = unique("combine")
    val echo = unique("echo")
    val results = CopyOnWriteArrayList<Map<String, String?>>()

    testNode("batches").use { bikesched ->
      bikesched.handleResultJobs<String, String>(echo) { payload -> payload.uppercase() }
      bikesched.handleJobs(parent) {
        val batch =
          startJobBatch<String, String>(
            "echo",
            echo,
            items = listOf(BatchItem("a", key = "first")),
            pairs = listOf("second" to "b"),
            payloads = listOf("c"),
          ) {
            item("d", key = "fourth")
          }
        results += batch.awaitResult().succeeded()
      }
      val jobId = bikesched.createJob(name = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      // the payload without a key is keyed by its position, the third item
      assertThat(results.single())
        .containsExactly(
          java.util.Map.entry("first", "A"),
          java.util.Map.entry("second", "B"),
          java.util.Map.entry("2", "C"),
          java.util.Map.entry("fourth", "D"),
        )
    }
  }

  @Test
  fun `a batch without results is typed Unit`() {
    val parent = unique("mails")
    val send = unique("send-mail")
    val sent = CopyOnWriteArrayList<String>()
    val allSucceeded = CopyOnWriteArrayList<Boolean>()

    testNode("batches").use { bikesched ->
      bikesched.handleJobs<String>(send) { to -> sent += to }
      bikesched.handleJobs(parent) {
        val result =
          startJobBatch<String, Unit>("mails", send, payloads = listOf("a@x.de", "b@x.de"))
            .awaitResult()
        allSucceeded += result.isAllSucceeded
      }
      val jobId = bikesched.createJob(name = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(sent).containsExactlyInAnyOrder("a@x.de", "b@x.de")
      assertThat(allSucceeded).containsExactly(true)
    }
  }

  @Test
  fun `a mixed batch reads each result with its own type`() {
    val parent = unique("checks")
    val virus = unique("virus-scan")
    val nsfw = unique("nsfw-check")
    val reports = CopyOnWriteArrayList<JobResult<ScanReport>>()

    testNode("batches").use { bikesched ->
      bikesched.handleResultJobs<Scan, ScanReport>(virus) { ScanReport("virus", true) }
      bikesched.handleResultJobs<Scan, Boolean>(nsfw) { false }
      bikesched.handleJobs<String>(parent) { fileId ->
        val checks =
          startJobBatch("checks", items = listOf(MixedBatchItem(virus, Scan(fileId), "virus"))) {
            item(nsfw, Scan(fileId), key = "nsfw")
          }
        val result = checks.awaitResult()
        reports += result.result<ScanReport>("virus")
        check(result.result<Boolean>("nsfw").orElseThrow() == false)
      }
      val jobId = bikesched.createJob(name = parent, payload = "f-1")

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      val report = reports.single()
      assertThat(report).isInstanceOf(JobResult.Succeeded::class.java)
      assertThat(report.orElseThrow()).isEqualTo(ScanReport("virus", true))
    }
  }

  @Test
  fun `a failing item with fail-fast fails the batch`() {
    val parent = unique("fail-fast")
    val item = unique("flaky")
    val unsucceeded = CopyOnWriteArrayList<Set<String>>()

    testNode("batches").use { bikesched ->
      bikesched.handleJobs<Int>(item) { n -> check(n != 1) { "item $n failed" } }
      bikesched.handleJobs(parent) {
        val batch =
          startJobBatch<Int, Unit>(
            "items",
            item,
            payloads = listOf(1),
            failFast = true,
            maxAttempts = 1,
          )
        unsucceeded += batch.awaitResult().unsucceeded().keys
      }
      val jobId = bikesched.createJob(name = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(unsucceeded).containsExactly(setOf("0"))
    }
  }

  @Test
  fun `unit payloads are rejected`() {
    val parent = unique("unit-payloads")
    val item = unique("unit-item")
    val failures = CopyOnWriteArrayList<Throwable>()

    testNode("batches").use { bikesched ->
      bikesched.handleJobs(item) {}
      bikesched.handleJobs(parent) {
        try {
          startJobBatch<Unit, Unit>("items", item, payloads = listOf(Unit))
        } catch (e: IllegalArgumentException) {
          failures += e
        }
      }
      val jobId = bikesched.createJob(name = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(failures.single()).hasMessageContaining("Unit payloads")
    }
  }

  @Test
  fun `a later run reads results with the batch's type whatever it adds now`() {
    val parent = unique("later-run")
    val render = unique("later-render")
    val runs = java.util.concurrent.atomic.AtomicInteger()
    val images = CopyOnWriteArrayList<Image>()

    testNode("batches").use { bikesched ->
      bikesched.handleResultJobs<Render, Image>(render) { r -> Image(r.format, r.format.length) }
      bikesched.handleJobs(parent) {
        val first = runs.incrementAndGet() == 1
        val batch =
          startJobBatch<Render, Image>("render", render) {
            // a later run adds nothing: the stored items count
            if (first) item(Render("img", "webp"), key = "webp")
          }
        images += batch.awaitResult().result<Image>("webp").orElseThrow()
        images += checkNotNull(batch.awaitResult()["webp"].orElseThrow())
      }
      val jobId = bikesched.createJob(name = parent)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(jobId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(images).containsExactly(Image("webp", 4), Image("webp", 4))
    }
  }

  @Test
  fun `await and awaitResult accept kotlin durations`() {
    val parentAwait = unique("batch-kotlin-timeout-await")
    val parentThrow = unique("batch-kotlin-timeout-throw")
    val slow = unique("batch-kotlin-slow")
    val outcomes = CopyOnWriteArrayList<JobResult<Unit>>()
    val thrown = CopyOnWriteArrayList<JobFailedException>()

    testNode("batches").use { bikesched ->
      bikesched.handleJobs(slow) { delay(5_000.milliseconds) }
      bikesched.handleJobs(parentAwait) {
        val batch = startJobBatch<Any?, Unit>("items", slow, payloads = listOf(null))
        outcomes += batch.awaitResult(300.milliseconds)["0"]
      }
      bikesched.handleJobs(parentThrow) {
        val batch = startJobBatch<Any?, Unit>("items", slow, payloads = listOf(null))
        try {
          batch.await(300.milliseconds)
        } catch (e: JobFailedException) {
          thrown += e
          throw e
        }
      }
      val awaitId = bikesched.createJob(name = parentAwait)
      val throwId = bikesched.createJob(name = parentThrow, maxAttempts = 1)

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(awaitId)?.state()).isEqualTo(JobState.SUCCEEDED)
        assertThat(bikesched.findJobOrNull(throwId)?.state()).isEqualTo(JobState.FAILED)
      }
      assertThat(outcomes.single()).isInstanceOf(JobResult.TimedOut::class.java)
      assertThat(thrown.single().result()).isInstanceOf(JobResult.TimedOut::class.java)
    }
  }

  @Test
  fun `awaitResult returns sealed Succeeded, Failed Incomplete and Failed`() {
    val parent = unique("sealed-result")
    val item = unique("sealed-item")
    val branches = CopyOnWriteArrayList<String>()

    testNode("batches").use { bikesched ->
      bikesched.handleResultJobs<String, String>(item) { payload ->
        check(!payload.startsWith("bad")) { "failed $payload" }
        payload.uppercase()
      }
      bikesched.handleJobs<List<String>>(parent) { payloads ->
        val result =
          startJobBatch<String, String>("items", item, payloads = payloads, maxAttempts = 1)
            .awaitResult()
        branches +=
          when (result) {
            is JobBatchResult.Succeeded -> "succeeded:${result.results()}"
            is JobBatchResult.Failed.Incomplete ->
              "incomplete:${result.succeeded().keys}:${result.unsucceeded().keys}"
            is JobBatchResult.Failed -> "failed:${result.unsucceeded().keys}"
          }
      }
      val okId = bikesched.createJob(name = parent, payload = listOf("a", "b"))
      val partialId = bikesched.createJob(name = parent, payload = listOf("a", "bad"))
      val allFailedId = bikesched.createJob(name = parent, payload = listOf("bad1", "bad2"))

      await().untilAsserted {
        assertThat(bikesched.findJobOrNull(okId)?.state()).isEqualTo(JobState.SUCCEEDED)
        assertThat(bikesched.findJobOrNull(partialId)?.state()).isEqualTo(JobState.SUCCEEDED)
        assertThat(bikesched.findJobOrNull(allFailedId)?.state()).isEqualTo(JobState.SUCCEEDED)
      }
      assertThat(branches)
        .containsExactlyInAnyOrder(
          "succeeded:[A, B]",
          "incomplete:[0]:[1]",
          "failed:[0, 1]",
        )
    }
  }
}
