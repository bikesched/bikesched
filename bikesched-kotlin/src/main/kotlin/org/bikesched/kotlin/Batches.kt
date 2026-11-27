// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.job.Job
import org.bikesched.job.JobBatch
import org.bikesched.job.JobBatchBuilder
import org.bikesched.job.JobBatchItem
import org.bikesched.job.JobBatchResult
import org.bikesched.job.JobBatchSettings
import org.bikesched.job.JobResult
import org.bikesched.job.MixedJobBatchBuilder
import org.bikesched.job.Step
import org.bikesched.job.TypedJob

/**
 * An item of a batch of one job name, for [startJobBatch]'s `items`.
 *
 * @property payload the item's payload, mapped to JSON by the configured `JsonMapper`
 * @property key the item's key, unique within the batch; `null` for its position (`"0"`, `"1"`, …)
 */
public data class BatchItem<out P>(val payload: P, val key: String? = null)

/**
 * An item of a mixed batch, for [startJobBatch]'s `items` without type arguments.
 *
 * @property jobName the item's job name
 * @property payload the item's payload, mapped to JSON by the configured `JsonMapper`
 * @property key the item's key, unique within the batch; `null` for its position (`"0"`, `"1"`, …)
 */
public data class MixedBatchItem(
  val jobName: String,
  val payload: Any?,
  val key: String? = null,
)

/** Collects the items of a batch of one job name, the receiver of [startJobBatch]'s lambda. */
public class BatchItems<in P> internal constructor(private val builder: JobBatchBuilder<P, *>) {

  /**
   * Adds an item.
   *
   * @param payload the item's payload
   * @param key the item's key, unique within the batch; `null` for its position
   */
  public fun item(payload: P, key: String? = null) {
    if (key == null) builder.add(payload) else builder.add(key, payload)
  }
}

/** Collects the items of a mixed batch, the receiver of [startJobBatch]'s lambda. */
public class MixedBatchItems internal constructor(private val builder: MixedJobBatchBuilder) {

  /**
   * Adds an item running the handler of [jobName].
   *
   * @param jobName the item's job name
   * @param payload the item's payload
   * @param key the item's key, unique within the batch; `null` for its position
   */
  public fun item(jobName: String, payload: Any?, key: String? = null) {
    if (key == null) builder.add(jobName, payload) else builder.add(jobName, key, payload)
  }
}

/**
 * Starts a batch of child jobs of [jobName] exactly once as a durable step of this job, see
 * `Job.newJobBatch(String, ResultJob)`; later runs return the same batch without starting again.
 * The items are distributed across the nodes, at most [parallelism] at a time, and [JobBatch.await]
 * returns all their outcomes with the results mapped to [R]:
 * ```
 * val renders = startJobBatch<Render, Image>("render", "render-format", parallelism = 4) {
 *     for (format in Format.ALL) item(Render(imageId, format), key = format.name)
 * }
 * val images: List<Image?> = renders.await().orElseThrow()
 * ```
 *
 * The items come from [items], [pairs], [payloads] and the lambda, in that order; a batch with none
 * is empty and ends right away. The lambda only lists items: it runs before the batch is stored and
 * only on the first run. `R = Unit` is a batch without results (every result `null`).
 *
 * @param stepName the batch's step name, unique within the job; item steps are named `stepName/key`
 * @param jobName the items' job name; it needs a handler on this node, or is one of its remote job
 *   names
 * @param items items with optional keys
 * @param pairs items as key to payload
 * @param payloads payloads keyed by their position
 * @param parallelism how many items may run at once; `null` for no limit
 * @param failFast ends the batch at the first item that does not succeed and cancels the rest
 * @param priority the items' priority, higher runs first; defaults to 0
 * @param maxAttempts the items' maximum number of attempts; defaults to 3
 * @param timeout the maximum duration of an item's attempt; defaults to the node's default
 * @param reference the items' reference; defaults to this job's
 * @param at when the items are due; with neither [at] nor [delay] they are due right away
 * @param delay how long from now until the items are due
 * @param compensation undoes an item if this job is cancelled or fails permanently, e.g. cancels
 *   it; runs once per started item, in reverse order
 * @param block adds items with [BatchItems.item]
 * @return the batch, the same on every run of this job
 * @throws IllegalArgumentException if both [at] and [delay] are set, keys repeat, the batch has
 *   more items than `maxJobBatchSize`, or the name has no handler and is no remote job name
 * @throws IllegalStateException if this job is an occurrence of a recurring job
 */
public inline fun <reified P, reified R> Job.startJobBatch(
  stepName: String,
  jobName: String,
  items: List<BatchItem<P>> = emptyList(),
  pairs: List<Pair<String, P>> = emptyList(),
  payloads: List<P> = emptyList(),
  parallelism: Int? = null,
  failFast: Boolean = false,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  reference: String? = null,
  at: Instant? = null,
  delay: Duration? = null,
  noinline compensation: (suspend (step: Step, item: JobBatchItem) -> Unit)? = null,
  noinline block: BatchItems<P>.() -> Unit = {},
): JobBatch<R> =
  startJobBatchOf(
    this,
    stepName,
    jobName,
    P::class.java,
    R::class.java,
    BatchOptions(
      parallelism,
      failFast,
      priority,
      maxAttempts,
      timeout,
      reference,
      at,
      delay,
      compensation,
    ),
  ) {
    items.forEach { item(it.payload, it.key) }
    pairs.forEach { (key, payload) -> item(payload, key) }
    payloads.forEach { item(it) }
    block()
  }

/**
 * Starts a mixed batch exactly once as a durable step of this job, each item naming its own job,
 * see `Job.newJobBatch(String)`; [result] reads an item's result typed:
 * ```
 * val checks = startJobBatch("checks") {
 *     item("virus-scan", Scan(fileId), key = "virus")
 *     item("nsfw-check", Scan(fileId), key = "nsfw")
 * }
 * val virus: JobResult<ScanReport> = checks.await().result<ScanReport>("virus")
 * ```
 *
 * The items come from [items] and the lambda, in that order. The other parameters are those of the
 * batch of one job name ([startJobBatch] with type arguments).
 *
 * @return the batch, the same on every run of this job
 */
public fun Job.startJobBatch(
  stepName: String,
  items: List<MixedBatchItem> = emptyList(),
  parallelism: Int? = null,
  failFast: Boolean = false,
  priority: Int? = null,
  maxAttempts: Int? = null,
  timeout: Duration? = null,
  reference: String? = null,
  at: Instant? = null,
  delay: Duration? = null,
  compensation: (suspend (step: Step, item: JobBatchItem) -> Unit)? = null,
  block: MixedBatchItems.() -> Unit = {},
): JobBatch<Any> {
  val options =
    BatchOptions(
      parallelism,
      failFast,
      priority,
      maxAttempts,
      timeout,
      reference,
      at,
      delay,
      compensation,
    )
  val builder = options.applyTo(newJobBatch(stepName))
  val collector = MixedBatchItems(builder)
  items.forEach { collector.item(it.jobName, it.payload, it.key) }
  collector.block()
  return when {
    at != null -> builder.schedule(at)
    delay != null -> builder.schedule(delay.toJavaDuration())
    else -> builder.schedule()
  }
}

/**
 * Returns the outcome of the item with [key], its result mapped to [T], see
 * `JobBatchResult.get(String, Class)`.
 *
 * @throws IllegalArgumentException if the batch has no item with the key
 */
public inline fun <reified T> JobBatchResult<*>.result(key: String): JobResult<T> =
  batchResultOf(this, key, T::class.java)

@PublishedApi
internal fun <T> batchResultOf(
  result: JobBatchResult<*>,
  key: String,
  type: Class<T>,
): JobResult<T> {
  if (type == Unit::class.java) {
    @Suppress("UNCHECKED_CAST")
    return result.get(key, Void::class.java) as JobResult<T>
  }
  return result.get(key, type)
}

@PublishedApi
internal class BatchOptions(
  private val parallelism: Int?,
  private val failFast: Boolean,
  private val priority: Int?,
  private val maxAttempts: Int?,
  private val timeout: Duration?,
  private val reference: String?,
  val at: Instant?,
  val delay: Duration?,
  private val compensation: (suspend (Step, JobBatchItem) -> Unit)?,
) {

  init {
    require(at == null || delay == null) { "at and delay are mutually exclusive" }
  }

  fun <B : JobBatchSettings<B>> applyTo(builder: B): B {
    var b = builder
    parallelism?.let { b = b.parallelism(it) }
    if (failFast) b = b.failFast()
    priority?.let { b = b.priority(it) }
    maxAttempts?.let { b = b.maxAttempts(it) }
    timeout?.let { b = b.timeout(it.toJavaDuration()) }
    reference?.let { b = b.reference(it) }
    compensation?.let { handler ->
      b = b.compensateWith { step, item -> blocking { handler(step, checkNotNull(item)) } }
    }
    return b
  }
}

@PublishedApi
internal fun <P, R> startJobBatchOf(
  job: Job,
  stepName: String,
  jobName: String,
  payloadType: Class<P>,
  resultType: Class<R>,
  options: BatchOptions,
  block: BatchItems<P>.() -> Unit,
): JobBatch<R> {
  // R = Unit: a batch without results, whose results are all null
  @Suppress("UNCHECKED_CAST")
  val typed =
    (if (resultType == Unit::class.java)
      job.newJobBatch(stepName, TypedJob.of(jobName, payloadType))
    else job.newJobBatch(stepName, TypedJob.of(jobName, payloadType, resultType)))
      as JobBatchBuilder<P, Any?>
  val builder = options.applyTo(typed)
  BatchItems(builder).block()
  val batch =
    when {
      options.at != null -> builder.schedule(options.at)
      options.delay != null -> builder.schedule(options.delay.toJavaDuration())
      else -> builder.schedule()
    }
  @Suppress("UNCHECKED_CAST")
  return batch as JobBatch<R>
}
