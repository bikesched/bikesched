package bikesched.kotlin

import bikesched.core.event.Event
import bikesched.core.event.EventTimeoutException
import bikesched.core.event.EventWait
import bikesched.core.event.TimeoutHandler
import bikesched.core.job.Job
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Returns the event's payload mapped to [T] by the configured `JsonMapper`, see
 * `Event.payload(Class)`.
 *
 * @throws NullPointerException if the event has no payload and [T] is not nullable
 */
public inline fun <reified T> Event.payload(): T = payload(T::class.java) as T

/**
 * Receives the event's payload mapped to [T], waiting for it if needed (the job is suspended
 * meanwhile), see `EventWait.receive(Class)`. Uses the node's default event timeout, if any.
 *
 * @throws NullPointerException if the event has no payload and [T] is not nullable
 */
public inline fun <reified T> EventWait.receive(): T = receive(T::class.java) as T

/**
 * Receives the event's payload mapped to [T], or the result of [onTimeout] if it does not arrive
 * within [timeout] (counted from the first wait), see `EventWait.timeout(Duration,
 * TimeoutHandler)`:
 * ```
 * val payment = waitForEvent("await-payment", "payment-received", orderId)
 *     .receive<Payment?>(1.hours) { null }
 * ```
 *
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 */
public inline fun <reified T> EventWait.receive(
  timeout: Duration,
  noinline onTimeout: () -> T,
): T = receiveWithTimeout(this, T::class.java, timeout, onTimeout) as T

/**
 * Receives the event's payload mapped to [T], or `null` if it does not arrive within [timeout]
 * (counted from the first wait) or has no payload.
 */
public inline fun <reified T : Any> EventWait.receiveOrNull(timeout: Duration): T? =
  receive<T?>(timeout) { null }

/**
 * Waits for an event as a durable step and returns its payload mapped to [T], see
 * `Job.waitForEvent(String, String, String)`. The job is suspended until the event arrives (or the
 * timeout passes) and its handler runs again from the start, completed steps and received events
 * returning their stored results:
 * ```
 * val payment = waitForEvent<Payment>(step = "await-payment", name = "payment-received", key = order.id)
 * val answer = waitForEvent<Answer?>(step = "await-answer", name = "answer", key = id, timeout = 1.hours) {
 *     null // no answer in time
 * }
 * ```
 *
 * Without [timeout], the node's default event timeout applies, if any. Once a timeout passes, the
 * result of [onTimeout] is returned; without [onTimeout] the job fails permanently with an
 * [EventTimeoutException], like with the default event timeout.
 *
 * @param step the step name, unique within the job
 * @param name the event name, e.g. `payment-received`
 * @param key identifies the event instance waited for, e.g. an order id
 * @param timeout how long to wait at most, counted from the first wait
 * @param onTimeout the result if the event does not arrive in time; requires a [timeout]
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 * @throws IllegalArgumentException if [onTimeout] is given without a [timeout]
 */
public inline fun <reified T> Job.waitForEvent(
  step: String,
  name: String,
  key: String,
  timeout: Duration? = null,
  noinline onTimeout: (() -> T)? = null,
): T = waitForEventOf(this, step, name, key, T::class.java, timeout, onTimeout) as T

@PublishedApi
internal fun <T> waitForEventOf(
  job: Job,
  step: String,
  name: String,
  key: String,
  type: Class<out T>,
  timeout: Duration?,
  onTimeout: (() -> T)?,
): Any? {
  require(onTimeout == null || timeout != null) { "onTimeout requires a timeout" }
  val wait = job.waitForEvent(step, name, key)
  if (timeout == null) {
    return wait.receive(type)
  }
  val handler: () -> T =
    onTimeout
      ?: {
        val message =
          "Waiting for event $name/$key in step '$step' timed out after ${timeout.toJavaDuration()}"
        job.fail(message).remainingAttempts(0) // waiting again would time out again
        throw EventTimeoutException(message)
      }
  return receiveWithTimeout(wait, type, timeout, handler)
}

@PublishedApi
internal fun <T> receiveWithTimeout(
  wait: EventWait,
  type: Class<out T>,
  timeout: Duration,
  onTimeout: () -> T,
): Any? = wait.timeout(timeout.toJavaDuration(), TimeoutHandler { onTimeout() }).receive(type)
