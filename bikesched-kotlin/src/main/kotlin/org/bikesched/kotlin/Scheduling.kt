// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.Bikesched
import org.bikesched.job.Job
import org.bikesched.job.JobHandler
import org.bikesched.job.JobHandlerSettings
import org.bikesched.job.JobSettings
import org.bikesched.job.Recurrence

// Kotlin-style entry points: one call with named arguments instead of a builder chain. They use
// the Java API underneath (registerJobHandler, newJob(...)...schedule(), newEvent(...).publish()).

/**
 * Registers the handler executing jobs of [type]. It runs with the [Job] as receiver and gets the
 * payload mapped to [T]:
 * ```
 * bikesched.handleJobs<SendMail>("send-mail") { mail ->
 *     val receipt = step<Receipt>("send") { mailer.send(mail) }
 * }
 * ```
 *
 * A job without payload fails with an [IllegalStateException] unless [T] is nullable.
 *
 * @return this instance
 * @throws IllegalArgumentException if the type is blank or already has a handler
 */
public inline fun <reified T> Bikesched.handleJobs(
  type: String,
  noinline handler: Job.(payload: T) -> Unit,
): Bikesched = registerJobHandler(type, typedJobHandler(type, T::class.java, null is T, handler))

/**
 * Registers the handler executing jobs of [type] that need no payload. It runs with the [Job] as
 * receiver:
 * ```
 * bikesched.handleJobs("cleanup") { cleanup.run() }
 * ```
 *
 * @return this instance
 * @throws IllegalArgumentException if the type is blank or already has a handler
 */
public fun Bikesched.handleJobs(type: String, handler: Job.() -> Unit): Bikesched =
  registerJobHandler(type, JobHandler { job -> job.handler() })

/**
 * Schedules a job without payload, see `Bikesched.newJob(String)`:
 * ```
 * bikesched.createJob(type = "cleanup", delay = 5.minutes)
 * bikesched.createJob(type = "cleanup", priority = 10) { cleanup.run() }
 * ```
 *
 * @param type the job type
 * @param at when the job is due; with neither [at] nor [delay] it is due right away
 * @param delay how long from now until the job is due
 * @param priority its priority, higher runs first; defaults to 0
 * @param maxAttempts its maximum number of attempts; defaults to 3
 * @param timeout the maximum duration of an attempt; defaults to the node's default
 * @param connection stores the job in the caller's transaction, see `JobBuilder.connection`
 * @param handler executes jobs of this type, registered unless the type already has a handler
 * @return the job id
 * @throws IllegalArgumentException if both [at] and [delay] are set, or the type has no handler
 */
public fun Bikesched.createJob(
  type: String,
  at: Instant? = null,
  delay: Duration? = null,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  connection: Connection? = null,
  handler: (Job.() -> Unit)? = null,
): UUID =
  scheduleJob(
    this,
    type,
    JobOptions(
      null,
      priority,
      maxAttempts,
      timeout,
      handler?.let { JobHandler { job -> job.it() } },
    ),
    at,
    delay,
    connection,
  )

/**
 * Schedules a job with a payload, mapped to JSON by the configured `JsonMapper`, see
 * `Bikesched.newJob(String)`:
 * ```
 * bikesched.createJob(type = "send-mail", payload = SendMail("a@b.de", "Hi"), delay = 5.minutes)
 * bikesched.createJob(type = "send-mail", payload = SendMail("a@b.de", "Hi")) { mail -> mailer.send(mail) }
 * ```
 *
 * The parameters are the same as for the job without payload; the [handler] gets the payload as
 * [T].
 *
 * @return the job id
 * @throws IllegalArgumentException if both [at] and [delay] are set, or the type has no handler
 */
public inline fun <reified T : Any> Bikesched.createJob(
  type: String,
  payload: T,
  at: Instant? = null,
  delay: Duration? = null,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  connection: Connection? = null,
  noinline handler: (Job.(payload: T) -> Unit)? = null,
): UUID =
  scheduleJob(
    this,
    type,
    JobOptions(
      payload,
      priority,
      maxAttempts,
      timeout,
      handler?.let { typedJobHandler(type, T::class.java, false, it) },
    ),
    at,
    delay,
    connection,
  )

/**
 * Registers a recurring job without payload, see `JobBuilder.schedule(Recurrence)`:
 * ```
 * bikesched.createRecurringJob(type = "cleanup", recurrence = every(1.hours)) { cleanup.run() }
 * ```
 *
 * @param type the job type; one recurring job per type
 * @param recurrence when it runs, e.g. [every] or `Recurrence.daily(...)`
 * @param firstRun when the first occurrence runs; with neither [firstRun] nor [delay] the
 *   recurrence's next run from now
 * @param delay how long from now until the first occurrence runs
 * @param priority the priority of its occurrences, higher runs first; defaults to 0
 * @param maxAttempts the maximum number of attempts per occurrence; defaults to 3
 * @param timeout the maximum duration of an attempt; defaults to the node's default
 * @param handler executes jobs of this type, registered unless the type already has a handler
 * @return the id of the pending occurrence
 * @throws IllegalArgumentException if both [firstRun] and [delay] are set, or the type has no
 *   handler
 */
public fun Bikesched.createRecurringJob(
  type: String,
  recurrence: Recurrence,
  firstRun: Instant? = null,
  delay: Duration? = null,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  handler: (Job.() -> Unit)? = null,
): UUID =
  registerRecurringJob(
    this,
    type,
    recurrence,
    JobOptions(
      null,
      priority,
      maxAttempts,
      timeout,
      handler?.let { JobHandler { job -> job.it() } },
    ),
    firstRun,
    delay,
  )

/**
 * Registers a recurring job with a payload (the same for every occurrence), see
 * `JobBuilder.schedule(Recurrence)`:
 * ```
 * bikesched.createRecurringJob(type = "report", recurrence = every(1.days), payload = Report("sales")) {
 *     report -> reports.send(report)
 * }
 * ```
 *
 * The parameters are the same as for the recurring job without payload; the [handler] gets the
 * payload as [T].
 *
 * @return the id of the pending occurrence
 * @throws IllegalArgumentException if both [firstRun] and [delay] are set, or the type has no
 *   handler
 */
public inline fun <reified T : Any> Bikesched.createRecurringJob(
  type: String,
  recurrence: Recurrence,
  payload: T,
  firstRun: Instant? = null,
  delay: Duration? = null,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  noinline handler: (Job.(payload: T) -> Unit)? = null,
): UUID =
  registerRecurringJob(
    this,
    type,
    recurrence,
    JobOptions(
      payload,
      priority,
      maxAttempts,
      timeout,
      handler?.let { typedJobHandler(type, T::class.java, false, it) },
    ),
    firstRun,
    delay,
  )

/**
 * Publishes an event to the steps waiting for it, see `Bikesched.newEvent(String, String)`:
 * ```
 * bikesched.publishEvent(name = "payment-received", key = order.id, payload = payment)
 * ```
 *
 * @param name the event name
 * @param key identifies the event instance waited for, e.g. an order id
 * @param payload the payload, mapped to JSON by the configured `JsonMapper`; may be `null`
 * @param retention how long steps starting to wait later still receive it, see
 *   `EventBuilder.retention`; defaults to the node's `eventRetention`; `Duration.ZERO` delivers it
 *   only to the steps waiting now
 * @param connection publishes the event in the caller's transaction, see `EventBuilder.connection`
 * @return the id of the published event
 */
public fun Bikesched.publishEvent(
  name: String,
  key: String,
  payload: Any? = null,
  retention: Duration? = null,
  connection: Connection? = null,
): UUID {
  val event = newEvent(name, key)
  payload?.let(event::payload)
  retention?.let { event.retention(it.toJavaDuration()) }
  connection?.let(event::connection)
  return event.publish()
}

/**
 * Publishes an event exactly once as a durable step of this job, see `Job.newEvent(String,
 * String)`.
 *
 * @return the id of the published event, the same on every run of the job
 */
public fun Job.publishEvent(
  name: String,
  key: String,
  payload: Any? = null,
  retention: Duration? = null,
  stepName: String? = null,
): UUID {
  val event = newEvent(name, key)
  payload?.let(event::payload)
  retention?.let { event.retention(it.toJavaDuration()) }
  stepName?.let(event::stepName)
  return event.publish()
}

/** The options shared by all job builders; `null` keeps the builder's default. */
@PublishedApi
internal class JobOptions(
  private val payload: Any?,
  private val priority: Int?,
  private val maxAttempts: Int?,
  private val timeout: Duration?,
  private val handler: JobHandler?,
) {

  fun <B : JobHandlerSettings<B, Any>> applyTo(builder: B): B {
    val b = applySettingsTo(builder)
    return handler?.let { b.handler(it) } ?: b
  }

  /** Applies all options but the handler, for child jobs, which have none. */
  fun <B : JobSettings<B, Any>> applySettingsTo(builder: B): B {
    var b = builder
    payload?.let { b = b.payload(it) }
    priority?.let { b = b.priority(it) }
    maxAttempts?.let { b = b.maxAttempts(it) }
    timeout?.let { b = b.timeout(it.toJavaDuration()) }
    return b
  }
}

@PublishedApi
internal fun scheduleJob(
  bikesched: Bikesched,
  type: String,
  options: JobOptions,
  at: Instant?,
  delay: Duration?,
  connection: Connection?,
): UUID {
  require(at == null || delay == null) { "at and delay are mutually exclusive" }
  val builder = bikesched.newJob(type)
  if (connection != null) {
    val transactional = options.applyTo(builder.connection(connection))
    return when {
      at != null -> transactional.schedule(at)
      delay != null -> transactional.schedule(delay.toJavaDuration())
      else -> transactional.schedule()
    }
  }
  val regular = options.applyTo(builder)
  return when {
    at != null -> regular.schedule(at)
    delay != null -> regular.schedule(delay.toJavaDuration())
    else -> regular.schedule()
  }
}

@PublishedApi
internal fun registerRecurringJob(
  bikesched: Bikesched,
  type: String,
  recurrence: Recurrence,
  options: JobOptions,
  firstRun: Instant?,
  delay: Duration?,
): UUID {
  require(firstRun == null || delay == null) { "firstRun and delay are mutually exclusive" }
  val builder = options.applyTo(bikesched.newJob(type))
  return when {
    firstRun != null -> builder.schedule(recurrence, firstRun)
    delay != null -> builder.schedule(recurrence, delay.toJavaDuration())
    else -> builder.schedule(recurrence)
  }
}

/** Maps the payload to [payloadType] and runs the typed [handler] with the job as receiver. */
@PublishedApi
internal fun <T> typedJobHandler(
  type: String,
  payloadType: Class<*>,
  nullable: Boolean,
  handler: Job.(T) -> Unit,
): JobHandler = JobHandler { job ->
  val payload = job.payload(payloadType)
  if (payload == null && !nullable) {
    throw IllegalStateException("Job ${job.id()} of type $type has no payload")
  }
  @Suppress("UNCHECKED_CAST") job.handler(payload as T)
}
