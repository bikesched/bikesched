// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.event.EventTimeoutException
import org.bikesched.event.EventWait
import org.bikesched.job.FailingStep
import org.bikesched.job.Job

/**
 * Waits for an event as a durable step and returns its payload mapped to [T], see
 * `Job.waitForEvent(String, String, String, Class)`. The job is suspended until the event arrives
 * (or the timeout passes) and its handler runs again from the start, completed steps and received
 * events returning their stored results (a received event's payload is stored with the step):
 * ```
 * val payment = waitForEvent<Payment>(step = "await-payment", name = "payment-received", key = order.id)
 * val answer = waitForEvent<Answer?>(step = "await-answer", name = "answer", key = id, timeout = 1.hours,
 *     onFailure = { it.resolve(null) }) // no answer in time
 * ```
 *
 * Without [timeout], the node's default event timeout applies, if any. Once a timeout passes, the
 * wait fails with an [EventTimeoutException], which fails the job without further attempts unless
 * [onFailure] resolves it (`failing.resolve(x)`): then the fallback is stored and returned, see
 * `EventWait.onFailure`.
 *
 * @param step the step name, unique within the job
 * @param name the event name, e.g. `payment-received`
 * @param key identifies the event instance waited for, e.g. an order id
 * @param timeout how long to wait at most, counted from the first wait
 * @param onFailure runs right away once the wait timed out (the step is `FAILING` meanwhile); may
 *   resolve it with a fallback result so the job continues; runs again if it throws, so it must be
 *   idempotent
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 */
public inline fun <reified T> Job.waitForEvent(
  step: String,
  name: String,
  key: String,
  timeout: Duration? = null,
  noinline onFailure: ((failing: FailingStep<T>) -> Unit)? = null,
): T = waitForEventOf(this, step, name, key, T::class.java, timeout, onFailure) as T

@PublishedApi
internal fun <T> waitForEventOf(
  job: Job,
  step: String,
  name: String,
  key: String,
  type: Class<*>,
  timeout: Duration?,
  onFailure: ((FailingStep<T>) -> Unit)?,
): Any? {
  @Suppress("UNCHECKED_CAST") val wait = job.waitForEvent(step, name, key, type) as EventWait<T>
  if (timeout != null) {
    wait.timeout(timeout.toJavaDuration())
  }
  if (onFailure != null) {
    wait.onFailure { failing -> onFailure(failing) }
  }
  return wait.receive()
}
