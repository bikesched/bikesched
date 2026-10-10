// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.util.UUID
import org.bikesched.Bikesched
import org.bikesched.Node
import org.bikesched.RetryBackoff
import org.bikesched.job.FailingStep
import org.bikesched.job.Job
import org.bikesched.job.JobInfo
import org.bikesched.job.Step

/**
 * Returns the payload mapped to [T] by the configured `JsonMapper`, see `Job.payload(Class)`. Use a
 * nullable type for jobs that may have no payload.
 *
 * @throws NullPointerException if the job has no payload and [T] is not nullable
 */
public inline fun <reified T> Job.payload(): T = payload(T::class.java) as T

/**
 * Executes a durable step whose result is stored as JSON and returned on later attempts instead of
 * executing the step again, see `Job.step(String, Class, StepHandler)`. A step returning [Unit]
 * stores no result:
 * ```
 * val payment = step<Payment>("charge") { payments.charge(order) }
 * step("send-confirmation") { mailer.send(order, payment) }
 * ```
 *
 * With a [compensation], the step is undone if the job is cancelled or fails permanently after the
 * step completed (a saga), see `StepBuilder.compensateWith`. The compensation receives the step and
 * its stored result ([Unit] for a [Unit] step):
 * ```
 * val reservation =
 *     step<Reservation>("reserve", compensation = { _, r -> payments.release(r) }) {
 *         payments.reserve(order)
 *     }
 * ```
 *
 * With [onFailure], a step that failed in the job's last attempt can be handled before the job
 * fails, e.g. resolved with a fallback result so the job continues, see `StepBuilder.onFailure`:
 * ```
 * val rate =
 *     step<Rate>("fetch-rate", onFailure = { failing -> failing.resolve(Rate.DEFAULT) }) {
 *         rates.fetch(currency)
 *     }
 * ```
 *
 * @param name the step name, unique within the job
 * @param compensation undoes the completed step; runs at least once, so it must be idempotent
 * @param retryBackoff the delay before the job is retried after this step failed, instead of the
 *   node's backoff, see `StepBuilder.retryBackoff`
 * @param onFailure runs when the step failed in the job's last attempt, before the step and the job
 *   are recorded as failed (the step is `FAILING` meanwhile); may resolve the step with a fallback
 *   result (`failing.resolve(x)`) so the job continues; runs again if it throws, so it must be
 *   idempotent, see `StepBuilder.onFailure`
 * @param handler executes the step; only called if the step has not completed in an earlier attempt
 * @throws NullPointerException if the step result is `null` and [T] is not nullable
 */
public inline fun <reified T> Job.step(
  name: String,
  noinline compensation: ((step: Step, result: T) -> Unit)? = null,
  retryBackoff: RetryBackoff? = null,
  noinline onFailure: ((failing: FailingStep<T>) -> Unit)? = null,
  noinline handler: (Step) -> T,
): T = runStep(this, name, T::class.java, compensation, retryBackoff, onFailure, handler) as T

/**
 * Reads a job, see `Bikesched.findJob(UUID)`.
 *
 * @return the job's id, type and state; `null` if there is no such job
 */
public fun Bikesched.findJobOrNull(jobId: UUID): JobInfo? = findJob(jobId).orElse(null)

/** The node's id once it polls for jobs, `null` before, see `Node.id()`. */
public val Node.idOrNull: UUID?
  get() = id().orElse(null)

@PublishedApi
internal fun <T> runStep(
  job: Job,
  name: String,
  type: Class<T>,
  compensation: ((Step, T) -> Unit)?,
  retryBackoff: RetryBackoff?,
  onFailure: ((FailingStep<T>) -> Unit)?,
  handler: (Step) -> T,
): Any? {
  if (type == Unit::class.java) {
    // A Unit step runs as a step without result (Void), so no Unit reaches the JsonMapper.
    val builder = job.newStep(name, Void::class.java)
    if (retryBackoff != null) {
      builder.retryBackoff(retryBackoff)
    }
    if (onFailure != null) {
      @Suppress("UNCHECKED_CAST")
      builder.onFailure { failing -> onFailure(UnitFailingStep(failing) as FailingStep<T>) }
    }
    if (compensation != null) {
      @Suppress("UNCHECKED_CAST")
      builder.compensateWith { step, _ -> compensation(step, Unit as T) }
    }
    builder.run { step ->
      handler(step)
      null
    }
    return Unit
  }
  val builder = job.newStep(name, type)
  if (retryBackoff != null) {
    builder.retryBackoff(retryBackoff)
  }
  if (onFailure != null) {
    builder.onFailure { failing -> onFailure(failing) }
  }
  if (compensation != null) {
    builder.compensateWith { step, result -> compensation(step, result) }
  }
  return builder.run { handler(it) }
}

/** A failing [Unit] step, run as a `Void` step: resolving it with [Unit] stores no result. */
internal class UnitFailingStep(private val delegate: FailingStep<Void>) : FailingStep<Unit> {
  override fun jobId(): UUID = delegate.jobId()

  override fun name(): String = delegate.name()

  override fun error(): String = delegate.error()

  override fun resolve(result: Unit) {
    delegate.resolve(null)
  }
}
