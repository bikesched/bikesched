// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.job.Job
import org.bikesched.job.JobResult
import org.bikesched.job.Step

/**
 * Starts a child job exactly once as a durable step of this job, see `Job.newJob(String)`; later
 * runs return the same child's id without scheduling again:
 * ```
 * val charge = startJob(type = "charge", payload = order.payment, stepName = "charge")
 * val receipt = awaitJob<Receipt>(charge).orElseThrow()
 * ```
 *
 * @param type the child's job type; it needs a handler on this node
 * @param payload the payload, mapped to JSON by the configured `JsonMapper`; may be `null`
 * @param stepName the step name, unique within the job; defaults to `start <type>`
 * @param at when the child is due; with neither [at] nor [delay] it is due right away
 * @param delay how long from now until the child is due
 * @param priority its priority, higher runs first; defaults to 0
 * @param maxAttempts its maximum number of attempts; defaults to 3
 * @param timeout the maximum duration of an attempt; defaults to the node's default
 * @param compensation undoes starting the child if this job is cancelled or fails permanently, e.g.
 *   cancels it; receives the child's id, see `StepJobBuilder.compensateWith`
 * @return the child's id, the same on every run of this job
 * @throws IllegalArgumentException if both [at] and [delay] are set, or the type has no handler
 * @throws IllegalStateException if this job is an occurrence of a recurring job
 */
public fun Job.startJob(
  type: String,
  payload: Any? = null,
  stepName: String? = null,
  at: Instant? = null,
  delay: Duration? = null,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  compensation: ((step: Step, childJobId: UUID) -> Unit)? = null,
): UUID {
  require(at == null || delay == null) { "at and delay are mutually exclusive" }
  val builder =
    JobOptions(payload, priority, maxAttempts, timeout, null).applySettingsTo(newJob(type))
  stepName?.let(builder::stepName)
  compensation?.let { handler -> builder.compensateWith { step, id -> handler(step, id) } }
  return when {
    at != null -> builder.schedule(at)
    delay != null -> builder.schedule(delay.toJavaDuration())
    else -> builder.schedule()
  }
}

/**
 * Waits for a child job started with [startJob] and returns its outcome with the result mapped to
 * [T], see `Job.waitForJob(UUID, Class)`. The job is suspended until the child ends (or the
 * [timeout] passes) and its handler runs again from the start:
 * ```
 * when (val outcome = awaitJob<Receipt>(charge, timeout = 1.hours)) {
 *     is JobResult.Succeeded -> ship(outcome.result())
 *     is JobResult.TimedOut -> cancel("payment took too long")
 *     else -> fail("payment failed: $outcome")
 * }
 * ```
 *
 * @param childJobId the id [startJob] returned
 * @param timeout how long to wait at most, counted from the first wait; `null` for no limit
 * @return the child's outcome; [JobResult.TimedOut] once the [timeout] passed
 * @throws IllegalArgumentException if the id is not one of this job's children
 */
public inline fun <reified T> Job.awaitJob(
  childJobId: UUID,
  timeout: Duration? = null,
): JobResult<T> = awaitJobOf(this, childJobId, T::class.java, timeout)

@PublishedApi
internal fun <T> awaitJobOf(
  job: Job,
  childJobId: UUID,
  type: Class<T>,
  timeout: Duration?,
): JobResult<T> =
  if (timeout == null) job.waitForJob(childJobId, type)
  else job.waitForJob(childJobId, type, timeout.toJavaDuration())
