// SPDX-License-Identifier: Apache-2.0
package org.bikesched.kotlin

import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.bikesched.Bikesched
import org.bikesched.JsonMapper
import org.bikesched.RetryBackoff
import org.bikesched.event.listener.EventListener
import org.bikesched.job.RecurrenceParser
import org.bikesched.job.listener.JobListener

/** Marks the bikesched configuration DSL, see [bikesched]. */
@DslMarker public annotation class BikeschedDsl

/**
 * Creates and starts a [Bikesched] instance, configured in Kotlin style:
 * ```
 * val bikesched = bikesched {
 *     dataSource = ds
 *     nodeName = "worker-1"
 *     maxConcurrentJobs = 20
 *     defaultJobTimeout = 5.minutes
 * }
 * ```
 *
 * Options left unset keep the defaults of `Bikesched.builder()`.
 *
 * @throws IllegalStateException if no [BikeschedSpec.dataSource] is set
 */
public fun bikesched(configure: BikeschedSpec.() -> Unit): Bikesched =
  BikeschedSpec().apply(configure).build()

/**
 * The options of [bikesched], mirroring `BikeschedBuilder`. Every option is optional except
 * [dataSource]; `null` keeps the builder's default.
 */
@BikeschedDsl
public class BikeschedSpec internal constructor() {

  /** The PostgreSQL data source (required). */
  public var dataSource: DataSource? = null

  /** The unique node name; defaults to the host name. */
  public var nodeName: String? = null

  /** The maximum number of jobs this node executes at the same time; defaults to 10. */
  public var maxConcurrentJobs: Int? = null

  /** Whether to start polling for jobs right away; defaults to `true`. */
  public var autoStartPolling: Boolean? = null

  /**
   * Interval between two polls for due jobs (see `BikeschedBuilder.pollInterval`); defaults to 10
   * seconds.
   */
  public var pollInterval: Duration? = null

  /**
   * Whether the node listens for due jobs and notifies the other nodes about the jobs it makes due
   * (see `BikeschedBuilder.listenForDueJobs`); defaults to `true`.
   */
  public var listenForDueJobs: Boolean? = null

  /**
   * How long the node collects due job types before notifying the other nodes (see
   * `BikeschedBuilder.notifyInterval`); defaults to 100 milliseconds.
   */
  public var notifyInterval: Duration? = null

  /**
   * Interval between the heartbeats this node writes while it polls (see
   * `BikeschedBuilder.heartbeatInterval`); defaults to 5 seconds.
   */
  public var heartbeatInterval: Duration? = null

  /**
   * Time without heartbeat after which a node is considered dead (see
   * `BikeschedBuilder.nodeTimeout`); defaults to 30 seconds, must be longer than
   * [heartbeatInterval].
   */
  public var nodeTimeout: Duration? = null

  /**
   * Maps payloads and step results to and from JSON; without one, only strings that already contain
   * JSON are accepted. See `bikesched-jackson` for Jackson.
   */
  public var jsonMapper: JsonMapper? = null

  /** The timeout of job attempts without their own timeout; defaults to none. */
  public var defaultJobTimeout: Duration? = null

  /** The timeout of event waits without their own timeout; defaults to none. */
  public var defaultEventTimeout: Duration? = null

  /** The delay of retries, see [fixedBackoff] and [exponentialBackoff]; defaults to none. */
  public var retryBackoff: RetryBackoff? = null

  /**
   * How often a failure handler runs before it is given up (see
   * `BikeschedBuilder.failureHandlerAttempts`); defaults to 3.
   */
  public var failureHandlerAttempts: Int? = null

  /**
   * How long events published through this node are kept, unless an event sets its own retention;
   * defaults to 7 days.
   */
  public var eventRetention: Duration? = null

  /** How often expired events are deleted; defaults to 1 hour. */
  public var eventCleanUpInterval: Duration? = null

  /**
   * How often the jobs of dead nodes are released and dead nodes removed; also the retry delay of
   * the one-time check for missing occurrences of recurring jobs. Defaults to 15 seconds.
   */
  public var nodeCleanUpInterval: Duration? = null

  /**
   * Enables the job history, keeping entries for this long (see `BikeschedBuilder.jobHistory`);
   * defaults to no history.
   */
  public var jobHistoryRetention: Duration? = null

  /** How often history entries older than [jobHistoryRetention] are deleted; defaults to 1 hour. */
  public var jobHistoryCleanUpInterval: Duration? = null

  /**
   * Deletes ended jobs with their steps once they finished this long ago (see
   * `BikeschedBuilder.jobRetention`); defaults to 7 days.
   */
  public var jobRetention: Duration? = null

  /** How often ended jobs older than [jobRetention] are deleted; defaults to 1 hour. */
  public var jobCleanUpInterval: Duration? = null

  /**
   * The maximum duration of a database statement of this node (see
   * `BikeschedBuilder.statementTimeout`); defaults to whatever the data source does.
   */
  public var statementTimeout: Duration? = null

  /**
   * How long after its timeout an attempt whose handler ignores the interrupt is abandoned (see
   * `BikeschedBuilder.hardTimeoutGrace`); defaults to no hard timeout.
   */
  public var hardTimeoutGrace: Duration? = null

  /**
   * Observes the jobs this node runs, e.g. `MicrometerJobListener` from `bikesched-micrometer` (see
   * `BikeschedBuilder.jobListener`); defaults to none.
   */
  public var jobListener: JobListener? = null

  /**
   * Observes the events published through this node, e.g. `MicrometerEventListener` from
   * `bikesched-micrometer` (see `BikeschedBuilder.eventListener`); defaults to none.
   */
  public var eventListener: EventListener? = null

  /**
   * Parse the recurrence expressions of `schedule(String)` and `@JobHandler(schedule = …)`,
   * replacing the default (see `BikeschedBuilder.recurrenceParsers`); defaults to
   * `RecurrenceParser.isoDuration()` only.
   */
  public var recurrenceParsers: List<RecurrenceParser>? = null

  internal fun build(): Bikesched {
    val builder = Bikesched.builder()
    dataSource?.let(builder::dataSource)
    nodeName?.let(builder::nodeName)
    maxConcurrentJobs?.let(builder::maxConcurrentJobs)
    autoStartPolling?.let(builder::autoStartPolling)
    pollInterval?.let { builder.pollInterval(it.toJavaDuration()) }
    listenForDueJobs?.let(builder::listenForDueJobs)
    notifyInterval?.let { builder.notifyInterval(it.toJavaDuration()) }
    heartbeatInterval?.let { builder.heartbeatInterval(it.toJavaDuration()) }
    nodeTimeout?.let { builder.nodeTimeout(it.toJavaDuration()) }
    jsonMapper?.let(builder::jsonMapper)
    defaultJobTimeout?.let { builder.defaultJobTimeout(it.toJavaDuration()) }
    defaultEventTimeout?.let { builder.defaultEventTimeout(it.toJavaDuration()) }
    retryBackoff?.let(builder::retryBackoff)
    failureHandlerAttempts?.let(builder::failureHandlerAttempts)
    eventRetention?.let { builder.eventRetention(it.toJavaDuration()) }
    eventCleanUpInterval?.let { builder.eventCleanUpInterval(it.toJavaDuration()) }
    nodeCleanUpInterval?.let { builder.nodeCleanUpInterval(it.toJavaDuration()) }
    jobHistoryRetention?.let { builder.jobHistory(it.toJavaDuration()) }
    jobHistoryCleanUpInterval?.let { builder.jobHistoryCleanUpInterval(it.toJavaDuration()) }
    jobRetention?.let { builder.jobRetention(it.toJavaDuration()) }
    jobCleanUpInterval?.let { builder.jobCleanUpInterval(it.toJavaDuration()) }
    statementTimeout?.let { builder.statementTimeout(it.toJavaDuration()) }
    hardTimeoutGrace?.let { builder.hardTimeoutGrace(it.toJavaDuration()) }
    jobListener?.let(builder::jobListener)
    eventListener?.let(builder::eventListener)
    recurrenceParsers?.let { builder.recurrenceParsers(*it.toTypedArray()) }
    return builder.build()
  }
}
