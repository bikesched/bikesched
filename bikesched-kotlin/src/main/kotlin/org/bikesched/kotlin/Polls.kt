// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.RetryBackoff
import org.bikesched.event.TimeoutHandler
import org.bikesched.job.Job
import org.bikesched.job.Poll
import org.bikesched.job.PollBuilder
import org.bikesched.job.PollTimeoutException
import org.bikesched.job.Step

/**
 * Polls as a durable step until [handler] returns [Poll.ready], checking every [interval], see
 * `Job.newPoll(String, Class, Duration)`. While a check is not ready, the job is suspended until
 * the next one and its handler runs again from the start; the ready value is stored and returned on
 * later runs:
 * ```
 * val report = poll<Report>("await-report", interval = 30.seconds, timeout = 2.hours) {
 *     when (val status = reports.status(reportId)) {
 *         is Done -> Poll.ready(status.report)
 *         else -> Poll.notReady()
 *     }
 * }
 * ```
 *
 * Once [timeout] has passed, the result of [onTimeout] is returned (not stored); without
 * [onTimeout] the job fails permanently with a [PollTimeoutException]. A poll of [Unit] runs as a
 * poll without a value (no [Unit] reaches the `JsonMapper`).
 *
 * @param name the step name, unique within the job
 * @param interval the time between checks, positive
 * @param timeout how long to poll at most, counted from the first check
 * @param onTimeout the result if the poll is not ready in time; requires a [timeout]
 * @param handler checks once; receives the step with the number of the check (`attempts`)
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 * @throws IllegalArgumentException if [onTimeout] is given without a [timeout]
 */
public inline fun <reified T> Job.poll(
  name: String,
  interval: Duration,
  timeout: Duration? = null,
  noinline onTimeout: (() -> T)? = null,
  noinline handler: (Step) -> Poll<T>,
): T =
  pollOf(T::class.java, timeout, onTimeout, handler) { type ->
    newPoll(name, type, interval.toJavaDuration())
  }
    as T

/**
 * Polls like [poll], with a growing [interval]: it is called after each check that is not ready,
 * with the number of that check, see `Job.newPoll(String, Class, RetryBackoff)`:
 * ```
 * val report = poll<Report>("await-report", interval = exponentialBackoff(5.seconds, 5.minutes)) {
 *     reports.find(reportId)?.let { Poll.ready(it) } ?: Poll.notReady()
 * }
 * ```
 *
 * @param name the step name, unique within the job
 * @param interval the time between checks, by the number of the check that was not ready
 * @param timeout how long to poll at most, counted from the first check
 * @param onTimeout the result if the poll is not ready in time; requires a [timeout]
 * @param handler checks once; receives the step with the number of the check (`attempts`)
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 * @throws IllegalArgumentException if [onTimeout] is given without a [timeout]
 */
public inline fun <reified T> Job.poll(
  name: String,
  interval: RetryBackoff,
  timeout: Duration? = null,
  noinline onTimeout: (() -> T)? = null,
  noinline handler: (Step) -> Poll<T>,
): T =
  pollOf(T::class.java, timeout, onTimeout, handler) { type -> newPoll(name, type, interval) } as T

@PublishedApi
internal fun <T> pollOf(
  type: Class<*>,
  timeout: Duration?,
  onTimeout: (() -> T)?,
  handler: (Step) -> Poll<T>,
  newPoll: (Class<*>) -> PollBuilder<*>,
): Any? {
  require(onTimeout == null || timeout != null) { "onTimeout requires a timeout" }
  if (type == Unit::class.java) {
    // A Unit poll runs as a poll without value (Void), so no Unit reaches the JsonMapper.
    @Suppress("UNCHECKED_CAST") val poll = newPoll(Void::class.java) as PollBuilder<Void?>
    withTimeout(poll, timeout, onTimeout?.let { fallback -> { fallback().let { null } } })
    poll.run { step ->
      val outcome = handler(step)
      if (outcome is Poll.NotReady<*>) Poll.NotReady(outcome.retryAfter()) else Poll.ready(null)
    }
    return Unit
  }
  @Suppress("UNCHECKED_CAST") val poll = newPoll(type) as PollBuilder<T>
  withTimeout(poll, timeout, onTimeout)
  return poll.run { handler(it) }
}

private fun <T> withTimeout(poll: PollBuilder<T>, timeout: Duration?, onTimeout: (() -> T)?) {
  if (timeout == null) {
    return
  }
  if (onTimeout == null) {
    poll.timeout(timeout.toJavaDuration())
  } else {
    poll.timeout(timeout.toJavaDuration(), TimeoutHandler { onTimeout() })
  }
}
