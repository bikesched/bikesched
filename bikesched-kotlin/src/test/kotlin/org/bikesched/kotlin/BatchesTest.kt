// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
        images += renders.await().orElseThrow()
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
        results += batch.await().succeeded()
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
          startJobBatch<String, Unit>("mails", send, payloads = listOf("a@x.de", "b@x.de")).await()
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
        val result = checks.await()
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
        unsucceeded += batch.await().unsucceeded().keys
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
}
