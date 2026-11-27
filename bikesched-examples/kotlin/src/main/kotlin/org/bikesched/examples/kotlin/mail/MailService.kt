// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.mail

import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** A mail to send, the payload of one batch item. */
@Serializable data class Mail(val to: String, val subject: String)

/** Sends mails, suspending like a real mail provider's coroutine client. */
interface MailService {
  suspend fun send(mail: Mail)
}

/** A mock mail service: a short `delay` as the provider's round trip, and a counter. */
class MockMailService : MailService {

  private val log = LoggerFactory.getLogger(MockMailService::class.java)

  private val counter = AtomicInteger()

  /** How many mails were sent. */
  val sent: Int
    get() = counter.get()

  override suspend fun send(mail: Mail) {
    delay(100.milliseconds)
    log.info("Sent mail {} to {}", counter.incrementAndGet(), mail.to)
  }
}
