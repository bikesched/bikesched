// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.simple

import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import org.bikesched.jackson.JacksonJsonMapper
import org.bikesched.kotlin.bikesched
import org.bikesched.kotlin.createJob
import org.bikesched.kotlin.publishEvent
import org.bikesched.kotlin.step
import org.bikesched.kotlin.waitForEvent
import org.bikesched.test.BikeschedTest
import org.postgresql.ds.PGSimpleDataSource

/** One job with two regular steps and an event wait, all in one class. */
object SimpleExample {
  @JvmStatic
  fun main(args: Array<String>) {
    val dataSource = PGSimpleDataSource().apply {
      serverNames = arrayOf("localhost")
      setPortNumbers(intArrayOf(System.getenv("POSTGRES_PORT")?.toInt() ?: 5432))
      databaseName = "bikesched"
      user = "bikesched"
      password = "bikesched"
    }

    bikesched(dataSource) {
      nodeName = "kotlin-simple-${UUID.randomUUID()}"
      pollInterval = 1.seconds
      jsonMapper = JacksonJsonMapper()
    }.use { node ->
      val eventKey = UUID.randomUUID().toString()
      val jobId = node.createJob(name = "kotlin-simple-job") {
        val greeting = step<String>("prepare-greeting") {
          println("Step 1: preparing the greeting")
          "Hello from Kotlin"
        }
        step<Unit>("print-greeting") { println("Step 2: $greeting") }
        val message = waitForEvent<String>(
          step = "await-event",
          name = "kotlin-simple-event",
          key = eventKey,
        )
        println("Step 3: received $message")
      }

      Executors.newSingleThreadScheduledExecutor().use { publisher ->
        publisher.schedule(
          {
            println("Publishing the event after 15 seconds")
            node.publishEvent(
              name = "kotlin-simple-event",
              key = eventKey,
              payload = "the delayed event",
            )
          },
          15,
          java.util.concurrent.TimeUnit.SECONDS,
        )
        BikeschedTest.awaitJob(node, jobId).timeout(Duration.ofSeconds(30)).untilSucceeded()
        println("Job $jobId finished successfully")
      }
    }
  }
}
