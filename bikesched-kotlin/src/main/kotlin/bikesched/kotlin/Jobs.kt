package bikesched.kotlin

import bikesched.core.Bikesched
import bikesched.core.Node
import bikesched.core.job.Job
import bikesched.core.job.JobInfo
import bikesched.core.job.Step
import java.util.UUID

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
 * @param name the step name, unique within the job
 * @param compensation undoes the completed step; runs at least once, so it must be idempotent
 * @param handler executes the step; only called if the step has not completed in an earlier attempt
 * @throws NullPointerException if the step result is `null` and [T] is not nullable
 */
public inline fun <reified T> Job.step(
  name: String,
  noinline compensation: ((step: Step, result: T) -> Unit)? = null,
  noinline handler: (Step) -> T,
): T = runStep(this, name, T::class.java, compensation, handler) as T

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
  handler: (Step) -> T,
): Any? {
  if (type == Unit::class.java) {
    // A Unit step runs as a step without result (Void), so no Unit reaches the JsonMapper.
    val builder = job.newStep(name, Void::class.java)
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
  if (compensation != null) {
    builder.compensateWith { step, result -> compensation(step, result) }
  }
  return builder.run { handler(it) }
}
