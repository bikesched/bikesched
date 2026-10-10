// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.RetryBackoff
import org.bikesched.job.FailingStep
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
 * Once [timeout] has passed, the job fails without further attempts with a [PollTimeoutException],
 * unless [onFailure] resolves the poll (`failing.resolve(x)`): then the fallback is stored and
 * returned, see `PollBuilder.onFailure`. A poll of [Unit] runs as a poll without a value (no [Unit]
 * reaches the `JsonMapper`).
 *
 * @param name the step name, unique within the job
 * @param interval the time between checks, positive
 * @param timeout how long to poll at most, counted from the first check
 * @param onFailure runs right away once the poll timed out, or when a check threw in the job's last
 *   attempt (the step is `FAILING` meanwhile); may resolve it with a fallback result so the job
 *   continues; runs again if it throws, so it must be idempotent
 * @param handler checks once; receives the step with the number of the check (`attempts`)
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 */
public inline fun <reified T> Job.poll(
  name: String,
  interval: Duration,
  timeout: Duration? = null,
  noinline onFailure: ((failing: FailingStep<T>) -> Unit)? = null,
  noinline handler: (Step) -> Poll<T>,
): T =
  pollOf(T::class.java, timeout, onFailure, handler) { type ->
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
 * @param onFailure handles a timeout or a check failing in the job's last attempt, see [poll]
 * @param handler checks once; receives the step with the number of the check (`attempts`)
 * @throws NullPointerException if the result is `null` and [T] is not nullable
 */
public inline fun <reified T> Job.poll(
  name: String,
  interval: RetryBackoff,
  timeout: Duration? = null,
  noinline onFailure: ((failing: FailingStep<T>) -> Unit)? = null,
  noinline handler: (Step) -> Poll<T>,
): T =
  pollOf(T::class.java, timeout, onFailure, handler) { type -> newPoll(name, type, interval) } as T

@PublishedApi
internal fun <T> pollOf(
  type: Class<*>,
  timeout: Duration?,
  onFailure: ((FailingStep<T>) -> Unit)?,
  handler: (Step) -> Poll<T>,
  newPoll: (Class<*>) -> PollBuilder<*>,
): Any? {
  if (type == Unit::class.java) {
    // A Unit poll runs as a poll without value (Void), so no Unit reaches the JsonMapper.
    @Suppress("UNCHECKED_CAST") val poll = newPoll(Void::class.java) as PollBuilder<Void>
    timeout?.let { poll.timeout(it.toJavaDuration()) }
    if (onFailure != null) {
      @Suppress("UNCHECKED_CAST")
      poll.onFailure { failing -> onFailure(UnitFailingStep(failing) as FailingStep<T>) }
    }
    poll.run { step ->
      val outcome = handler(step)
      if (outcome is Poll.NotReady<*>) Poll.NotReady(outcome.retryAfter()) else Poll.ready(null)
    }
    return Unit
  }
  @Suppress("UNCHECKED_CAST") val poll = newPoll(type) as PollBuilder<T>
  timeout?.let { poll.timeout(it.toJavaDuration()) }
  if (onFailure != null) {
    poll.onFailure { failing -> onFailure(failing) }
  }
  return poll.run { handler(it) }
}
