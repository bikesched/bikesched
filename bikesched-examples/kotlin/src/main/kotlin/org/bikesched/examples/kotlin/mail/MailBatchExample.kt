// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.mail

import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.bikesched.Bikesched
import org.bikesched.examples.kotlin.shop.KotlinxSerializationJsonMapper
import org.bikesched.kotlin.bikesched
import org.bikesched.kotlin.createJob
import org.bikesched.kotlin.handleJobs
import org.bikesched.kotlin.startJobBatch
import org.bikesched.test.BikeschedTest
import org.postgresql.ds.PGSimpleDataSource

/** The job names: one job sends the newsletter, one child job per mail. */
const val SEND_NEWSLETTER = "kotlin-mail-newsletter"
const val SEND_MAIL = "kotlin-mail-send"

/** The newsletter's readers. */
val READERS: List<String> = (1..100).map { "reader$it@example.com" }

/**
 * Registers the handlers: the newsletter job sends a mail to every reader, in batches of ten. Each
 * batch is a durable step: its ten mails run as child jobs on any node, and the job waits for the
 * batch (without holding a thread) before it starts the next one.
 */
fun registerMailHandlers(node: Bikesched, mailService: MailService) {
  node.handleJobs<Mail>(SEND_MAIL) { mail -> mailService.send(mail) }
  node.handleJobs<String>(SEND_NEWSLETTER) { subject ->
    READERS.chunked(10).forEachIndexed { index, readers ->
      // Started once; a later run of this job gets the stored batch back.
      val batch =
        startJobBatch<Mail, Unit>("mails-$index", SEND_MAIL) {
          for (reader in readers) item(Mail(reader, subject), key = reader)
        }
      // Throws if a mail failed for good: the job fails and can be retried.
      batch.await().orElseThrow()
    }
  }
}

/** Sends a newsletter to 100 readers in ten batches of ten mails. */
object MailBatchExample {
  @JvmStatic
  fun main(args: Array<String>) {
    val dataSource =
      PGSimpleDataSource().apply {
        serverNames = arrayOf("localhost")
        setPortNumbers(intArrayOf(System.getenv("POSTGRES_PORT")?.toInt() ?: 5432))
        databaseName = "bikesched"
        user = "bikesched"
        password = "bikesched"
      }
    val mailService = MockMailService()

    bikesched(dataSource) {
        nodeName = "kotlin-mail-${UUID.randomUUID()}"
        pollInterval = 1.seconds
        jsonMapper = KotlinxSerializationJsonMapper(Json)
      }
      .use { node ->
        registerMailHandlers(node, mailService)
        val jobId = node.createJob(name = SEND_NEWSLETTER, payload = "bikesched news")

        BikeschedTest.awaitJob(node, jobId).timeout(Duration.ofSeconds(60)).untilSucceeded()
        println("Newsletter job $jobId sent ${mailService.sent} mails")
      }
  }
}
